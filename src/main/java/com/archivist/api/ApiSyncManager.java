package com.archivist.api;

import com.archivist.config.ArchivistConfig;
import com.archivist.config.ArchivistConfig.ApiEndpointConfig;
import com.archivist.data.EventBus;
import com.archivist.data.JsonLogger;
import com.archivist.data.LogEvent;
import com.archivist.data.ServerLogData;
import com.archivist.util.ArchivistExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Orchestrates synchronization of server log data with configured API endpoints.
 *
 * <p>All I/O runs on {@link ArchivistExecutor} so it never blocks the render thread.
 * Failed pushes go into an offline queue that drains on the next successful push.</p>
 *
 * <p>No hardcoded URLs. All endpoints come from {@link ArchivistConfig#apiEndpoints}.</p>
 */
public final class ApiSyncManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public enum Status {
        NOT_CONFIGURED,
        CONNECTING,
        CONNECTED,
        UPLOADING,
        ERROR,
        DISCONNECTED
    }

    private final Supplier<ArchivistConfig> configSupplier;
    private final EventBus eventBus;

    private volatile Status status = Status.NOT_CONFIGURED;
    private volatile String statusMessage = "Not configured";

    private final ConcurrentLinkedQueue<ServerLogData> offlineQueue = new ConcurrentLinkedQueue<>();
    private ApiClient client;

    // Rate limiting (uses nanoTime to avoid wall-clock jumps)
    private volatile long lastPushTime = 0;
    private static final long MIN_PUSH_INTERVAL_MS = 5000;
    private static final long MIN_PUSH_INTERVAL_NS = MIN_PUSH_INTERVAL_MS * 1_000_000L;

    // Push debouncing
    private volatile boolean pendingPush = false;
    private volatile long pendingPushTime = 0;
    private volatile ServerLogData pendingPushData = null;
    private static final long DEBOUNCE_MS = 5000;

    /**
     * @param configSupplier provides the current config (called on IO thread)
     * @param eventBus       for posting DB_SYNC events
     */
    public ApiSyncManager(Supplier<ArchivistConfig> configSupplier, EventBus eventBus) {
        this.configSupplier = configSupplier;
        this.eventBus = eventBus;
    }

    // ── Status ──────────────────────────────────────────────────────────────

    public Status getStatus() { return status; }
    public String getStatusMessage() { return statusMessage; }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Called when the player joins a server. Resets transient state.
     */
    public void onServerJoin() {
        offlineQueue.clear();
        status = Status.NOT_CONFIGURED;
        statusMessage = "Not configured";
        client = null;
    }

    /**
     * Called when the player disconnects. If any endpoint has autoPush, queues for upload.
     * Respects lobby confidence filter — won't auto-push low-confidence data.
     *
     * @param data             the session data to push
     * @param sessionConfidence current session confidence (0.0-1.0), or -1 to skip filter
     */
    public void onDisconnect(ServerLogData data, double sessionConfidence) {
        if (data == null) return;

        ArchivistConfig config = configSupplier.get();
        if (config == null) return;

        // Lobby filter: skip auto-push if confidence below threshold
        if (sessionConfidence >= 0 && sessionConfidence < config.autoPushMinConfidence) {
            postEvent(LogEvent.Type.DB_SYNC, String.format(
                    "Skipped auto-push: lobby detected (%.0f%% < %.0f%% threshold)",
                    sessionConfidence * 100, config.autoPushMinConfidence * 100));
            return;
        }

        List<ApiEndpointConfig> endpoints = getEnabledEndpoints(config);
        boolean anyAutoPush = false;
        if (config.autoUploadOnLog && !endpoints.isEmpty()) {
            anyAutoPush = true;
        } else {
            for (ApiEndpointConfig ep : endpoints) {
                if (ep.autoPush) {
                    anyAutoPush = true;
                    break;
                }
            }
        }

        if (anyAutoPush) {
            pushData(data);
        }
    }

    /** @deprecated Use {@link #onDisconnect(ServerLogData, double)} instead. */
    public void onDisconnect(ServerLogData data) {
        onDisconnect(data, -1);
    }

    // ── Push ────────────────────────────────────────────────────────────────

    /**
     * Pushes a single log entry to all enabled endpoints. Non-blocking.
     */
    public void pushData(ServerLogData data) {
        pushData(data, null);
    }

    /**
     * Pushes a single log entry with optional callback for GUI status updates.
     */
    public void pushData(ServerLogData data, Consumer<ApiResponse> callback) {
        if (data == null) return;
        if (isRateLimited()) {
            postEvent(LogEvent.Type.DB_SYNC, "Push rate limited, try again shortly");
            if (callback != null) callback.accept(ApiResponse.error("Rate limited"));
            return;
        }

        ArchivistConfig config = configSupplier.get();
        List<ApiEndpointConfig> endpoints = getEnabledEndpoints(config);

        if (endpoints.isEmpty()) {
            setStatus(Status.NOT_CONFIGURED, "No API endpoints configured");
            postEvent(LogEvent.Type.DB_SYNC, "No API endpoints configured");
            if (callback != null) callback.accept(ApiResponse.error("No API endpoints configured"));
            return;
        }

        lastPushTime = System.nanoTime();
        ArchivistExecutor.execute(() -> {
            boolean anySuccess = false;
            ApiResponse lastResponse = null;

            for (ApiEndpointConfig ep : endpoints) {
                try {
                    setStatus(Status.UPLOADING, "Pushing to " + ep.name + "...");
                    postEvent(LogEvent.Type.DB_SYNC, "Pushing to " + ep.name + "...");

                    lastResponse = pushToEndpoint(ep, data);

                    if (lastResponse.success()) {
                        anySuccess = true;
                        postEvent(LogEvent.Type.DB_SYNC, "Push OK to " + ep.name + " (" + lastResponse.statusCode() + ")");
                    } else {
                        postEvent(LogEvent.Type.ERROR, "Push to " + ep.name + " failed: HTTP " + lastResponse.statusCode()
                                + " - " + truncate(lastResponse.body(), 100));
                    }
                } catch (Exception e) {
                    lastResponse = ApiResponse.error(e.getMessage());
                    postEvent(LogEvent.Type.ERROR, "Push to " + ep.name + " failed: " + e.getMessage());
                }
            }

            if (anySuccess) {
                setStatus(Status.CONNECTED, "Push complete");
                // Drain offline queue on success
                drainOfflineQueue(endpoints);
            } else {
                setStatus(Status.ERROR, "All pushes failed");
                offlineQueue.add(data);
            }

            if (callback != null) {
                ApiResponse finalResponse = lastResponse;
                callback.accept(finalResponse != null ? finalResponse : ApiResponse.error("No response"));
            }
        });
    }

    /**
     * Pushes all local log files to all enabled endpoints. Non-blocking.
     */
    public void pushAll() {
        pushAll(null);
    }

    /**
     * Pushes all local logs with optional callback.
     */
    public void pushAll(Consumer<ApiResponse> callback) {
        ArchivistExecutor.execute(() -> {
            List<ServerLogData> logs = JsonLogger.readAllLogs();
            if (logs.isEmpty()) {
                postEvent(LogEvent.Type.DB_SYNC, "No local logs to push");
                if (callback != null) callback.accept(ApiResponse.error("No local logs"));
                return;
            }

            ArchivistConfig config = configSupplier.get();
            List<ApiEndpointConfig> endpoints = getEnabledEndpoints(config);
            if (endpoints.isEmpty()) {
                setStatus(Status.NOT_CONFIGURED, "No API endpoints configured");
                postEvent(LogEvent.Type.DB_SYNC, "No API endpoints configured");
                if (callback != null) callback.accept(ApiResponse.error("No API endpoints configured"));
                return;
            }

            setStatus(Status.UPLOADING, "Pushing " + logs.size() + " logs...");
            postEvent(LogEvent.Type.DB_SYNC, "Pushing " + logs.size() + " local logs...");

            int success = 0;
            int fail = 0;
            ApiResponse lastResponse = null;

            for (ServerLogData data : logs) {
                for (ApiEndpointConfig ep : endpoints) {
                    try {
                        lastResponse = pushToEndpoint(ep, data);
                        if (lastResponse.success()) {
                            success++;
                        } else {
                            fail++;
                        }
                    } catch (Exception e) {
                        fail++;
                        lastResponse = ApiResponse.error(e.getMessage());
                    }
                }
            }

            String msg = "Pushed " + success + "/" + (success + fail)
                    + (fail > 0 ? " (" + fail + " failed)" : "");
            setStatus(fail > 0 ? Status.ERROR : Status.CONNECTED, msg);
            postEvent(LogEvent.Type.DB_SYNC, msg);

            if (callback != null) {
                callback.accept(lastResponse != null ? lastResponse : ApiResponse.error("No response"));
            }
        });
    }

    // ── Download ────────────────────────────────────────────────────────────

    /**
     * Downloads logs from all enabled endpoints and saves to disk. Non-blocking.
     */
    public void downloadAll() {
        downloadAll(null);
    }

    /**
     * Downloads with optional callback.
     */
    public void downloadAll(Consumer<ApiResponse> callback) {
        ArchivistConfig config = configSupplier.get();
        List<ApiEndpointConfig> endpoints = getEnabledEndpoints(config);

        if (endpoints.isEmpty()) {
            postEvent(LogEvent.Type.DB_SYNC, "No API endpoints configured");
            if (callback != null) callback.accept(ApiResponse.error("No API endpoints configured"));
            return;
        }

        ArchivistExecutor.execute(() -> {
            ApiResponse lastResponse = null;

            for (ApiEndpointConfig ep : endpoints) {
                try {
                    String dlUrl = normalizeUrl(ep.url, ep.downloadEndpoint);
                    postEvent(LogEvent.Type.DB_SYNC, "Downloading from " + ep.name + "...");

                    lastResponse = getClient().get(dlUrl, decodeHeaders(ep));

                    if (lastResponse.success()) {
                        Path logDir = JsonLogger.getLogsDirectory().getParent().resolve("downloaded");
                        Files.createDirectories(logDir);
                        String fileName = "api_download_" + ep.name.replaceAll("[^a-zA-Z0-9_-]", "_")
                                + "_" + LocalDateTime.now().format(FILE_TS) + ".json";
                        Files.writeString(logDir.resolve(fileName), lastResponse.body());
                        postEvent(LogEvent.Type.DB_SYNC, "Downloaded and saved: " + fileName);
                    } else {
                        postEvent(LogEvent.Type.ERROR, "Download from " + ep.name
                                + " failed: HTTP " + lastResponse.statusCode());
                    }
                } catch (Exception e) {
                    lastResponse = ApiResponse.error(e.getMessage());
                    postEvent(LogEvent.Type.ERROR, "Download from " + ep.name + " failed: " + e.getMessage());
                }
            }

            if (callback != null) {
                callback.accept(lastResponse != null ? lastResponse : ApiResponse.error("No response"));
            }
        });
    }

    // ── Test ────────────────────────────────────────────────────────────────

    /**
     * Tests connectivity to a specific endpoint. Non-blocking.
     */
    public void testConnection(ApiEndpointConfig endpoint) {
        testConnection(endpoint, null);
    }

    /**
     * Tests connectivity with optional callback.
     */
    public void testConnection(ApiEndpointConfig endpoint, Consumer<ApiResponse> callback) {
        if (endpoint == null || endpoint.url == null || endpoint.url.isBlank()) {
            postEvent(LogEvent.Type.DB_SYNC, "No URL configured for endpoint");
            if (callback != null) callback.accept(ApiResponse.error("No URL configured"));
            return;
        }

        ArchivistExecutor.execute(() -> {
            String type = endpoint.adapterType != null ? endpoint.adapterType.toUpperCase() : "REST";
            setStatus(Status.CONNECTING, "Testing " + endpoint.name + "...");
            postEvent(LogEvent.Type.DB_SYNC, "Testing " + endpoint.name + " (" + type + ")...");

            try {
                if ("REST".equals(type)) {
                    // REST: try GET to download endpoint first, fall back to push endpoint
                    Map<String, String> hdrs = decodeHeaders(endpoint);
                    String dlUrl = normalizeUrl(endpoint.url, endpoint.downloadEndpoint);
                    ApiResponse response = getClient().get(dlUrl, hdrs);

                    // If download endpoint doesn't exist (404), try push endpoint
                    if (response.statusCode() == 404 || response.statusCode() == 405) {
                        String pushUrl = normalizeUrl(endpoint.url, endpoint.pushEndpoint);
                        // Send a minimal GET to check connectivity — server may 405 but that proves it's reachable
                        response = getClient().get(pushUrl, hdrs);
                        // A 405 Method Not Allowed on the push endpoint means the server is reachable
                        if (response.statusCode() == 405 || response.statusCode() == 400) {
                            response = ApiResponse.of(200, "Server reachable (push endpoint active)");
                        }
                    }

                    if (response.success()) {
                        setStatus(Status.CONNECTED, "Connection OK");
                        postEvent(LogEvent.Type.DB_SYNC, "Connection OK (" + response.statusCode() + ")");
                    } else if (response.statusCode() == 0) {
                        setStatus(Status.ERROR, "Connection failed");
                        postEvent(LogEvent.Type.ERROR, "Connection failed: " + response.body());
                    } else {
                        setStatus(Status.ERROR, "HTTP " + response.statusCode());
                        postEvent(LogEvent.Type.ERROR, "Connection failed: HTTP " + response.statusCode()
                                + " - " + truncate(response.body(), 200));
                    }
                    if (callback != null) callback.accept(response);
                } else if ("DISCORD".equals(type)) {
                    DatabaseAdapter adapter = new DiscordBotAdapter();
                    adapter.connect(endpoint.url, decodeFirstAuthValue(endpoint));
                    boolean ok = adapter.testConnection();
                    adapter.disconnect();
                    if (ok) {
                        setStatus(Status.CONNECTED, "Connection OK");
                        postEvent(LogEvent.Type.DB_SYNC, type + " connection OK");
                        if (callback != null) callback.accept(ApiResponse.of(200, "OK"));
                    } else {
                        setStatus(Status.ERROR, "Test failed");
                        postEvent(LogEvent.Type.ERROR, type + " test failed");
                        if (callback != null) callback.accept(ApiResponse.error("Test failed"));
                    }
                } else {
                    postEvent(LogEvent.Type.ERROR, "Unknown adapter type: " + type);
                    if (callback != null) callback.accept(ApiResponse.error("Unknown adapter type"));
                }
            } catch (Exception e) {
                setStatus(Status.ERROR, e.getMessage());
                postEvent(LogEvent.Type.ERROR, "Test failed: " + e.getMessage());
                if (callback != null) callback.accept(ApiResponse.error(e.getMessage()));
            }
        });
    }

    // ── Reset ───────────────────────────────────────────────────────────────

    /**
     * Resets remote logs on a specific endpoint. Non-blocking.
     */
    public void resetRemote(ApiEndpointConfig endpoint) {
        resetRemote(endpoint, null);
    }

    /**
     * Resets with optional callback.
     */
    public void resetRemote(ApiEndpointConfig endpoint, Consumer<ApiResponse> callback) {
        if (endpoint == null || !isEndpointConfigured(endpoint)) {
            postEvent(LogEvent.Type.DB_SYNC, "Endpoint not configured for reset");
            if (callback != null) callback.accept(ApiResponse.error("Endpoint not configured"));
            return;
        }

        ArchivistExecutor.execute(() -> {
            try {
                String resetUrl = normalizeUrl(endpoint.url, endpoint.resetEndpoint);
                postEvent(LogEvent.Type.DB_SYNC, "Resetting remote logs on " + endpoint.name + "...");

                String resetKey = decodeResetKey(endpoint);
                Map<String, String> headers = decodeHeaders(endpoint);
                if (!resetKey.isEmpty()) {
                    headers.put("X-Reset-Key", resetKey);
                }

                String body = resetKey.isEmpty() ? "{}" : "{\"resetKey\":\"" + resetKey + "\"}";
                ApiResponse response = getClient().post(resetUrl, body, headers);

                if (response.success()) {
                    postEvent(LogEvent.Type.DB_SYNC, "Remote logs reset OK (" + response.statusCode() + ")");
                } else {
                    postEvent(LogEvent.Type.ERROR, "Reset failed: HTTP " + response.statusCode()
                            + " - " + truncate(response.body(), 100));
                }

                if (callback != null) callback.accept(response);
            } catch (Exception e) {
                postEvent(LogEvent.Type.ERROR, "Reset failed: " + e.getMessage());
                if (callback != null) callback.accept(ApiResponse.error(e.getMessage()));
            }
        });
    }

    // ── Panic Disconnect ─────────────────────────────────────────────────

    /**
     * Emergency disconnect: disables all endpoints, clears queue, cancels state.
     * Call from any thread.
     */
    public void panicDisconnect() {
        ArchivistConfig config = configSupplier.get();
        if (config != null) {
            for (ApiEndpointConfig ep : config.apiEndpoints) {
                ep.enabled = false;
            }
            config.archivistWebEnabled = false;
        }
        offlineQueue.clear();
        pendingPush = false;
        pendingPushData = null;
        client = null;
        setStatus(Status.DISCONNECTED, "Emergency disconnect");
        postEvent(LogEvent.Type.DB_SYNC, "Emergency disconnect: all endpoints disabled");
    }

    // ── Rate Limiting ──────────────────────────────────────────────────

    private boolean isRateLimited() {
        long now = System.nanoTime();
        long elapsedNs = now - lastPushTime;
        if (elapsedNs < MIN_PUSH_INTERVAL_NS) {
            LOGGER.debug("[Archivist] Push rate limited ({}ms since last)", elapsedNs / 1_000_000L);
            return true;
        }
        return false;
    }

    // ── Debounced Push ─────────────────────────────────────────────────

    /**
     * Schedules a debounced push. Multiple calls within DEBOUNCE_MS collapse into one.
     */
    public void debouncedPush(ServerLogData data) {
        pendingPushData = data;
        pendingPushTime = System.currentTimeMillis();
        pendingPush = true;
    }

    /**
     * Call from client tick. Flushes debounced push if timer expired.
     */
    public void tick() {
        if (pendingPush && System.currentTimeMillis() - pendingPushTime >= DEBOUNCE_MS) {
            ServerLogData data = pendingPushData;
            pendingPush = false;
            pendingPushData = null;
            if (data != null) {
                pushData(data);
            }
        }
    }

    // ── Queue Info ─────────────────────────────────────────────────────

    public int getQueueSize() {
        return offlineQueue.size();
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private ApiClient getClient() {
        if (client == null) {
            client = new ApiClient();
        }
        return client;
    }

    /**
     * Recreate the HTTP client (e.g. after config change).
     */
    public void refreshClient() {
        client = null;
    }

    private ApiResponse pushToEndpoint(ApiEndpointConfig ep, ServerLogData data) {
        String type = ep.adapterType != null ? ep.adapterType.toUpperCase() : "REST";
        return switch (type) {
            case "DISCORD" -> pushViaAdapter(new DiscordBotAdapter(), ep, data);
            default -> pushViaRest(ep, data);
        };
    }

    private ApiResponse pushViaRest(ApiEndpointConfig ep, ServerLogData data) {
        // Skip push if server IP hasn't been resolved
        String ip = data.serverInfo().ip();
        if (ip == null || ip.isBlank() || "unknown".equals(ip)) {
            LOGGER.warn("[Archivist] Skipping push: server IP not resolved ('{}')", ip);
            return ApiResponse.error("Server IP not resolved — cannot push unresolved data");
        }

        String pushUrl = normalizeUrl(ep.url, ep.pushEndpoint);
        String json = RestAdapter.buildPushPayload(data);
        Map<String, String> headers = decodeHeaders(ep);

        String method = ep.method != null ? ep.method.toUpperCase() : "POST";
        return switch (method) {
            case "PUT" -> getClient().put(pushUrl, json, headers);
            case "PATCH" -> getClient().patch(pushUrl, json, headers);
            default -> getClient().post(pushUrl, json, headers);
        };
    }

    private ApiResponse pushViaAdapter(DatabaseAdapter adapter, ApiEndpointConfig ep, ServerLogData data) {
        try {
            String authValue = decodeFirstAuthValue(ep);
            adapter.connect(ep.url, authValue);
            adapter.upload(data);
            adapter.disconnect();
            return ApiResponse.of(200, "OK");
        } catch (Exception e) {
            LOGGER.warn("[Archivist] Adapter push failed: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        }
    }

    private static String decodeFirstAuthValue(ApiEndpointConfig ep) {
        Map<String, String> headers = decodeHeaders(ep);
        for (String value : headers.values()) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private void drainOfflineQueue(List<ApiEndpointConfig> endpoints) {
        while (!offlineQueue.isEmpty()) {
            ServerLogData queued = offlineQueue.poll();
            if (queued == null) break;

            boolean anySuccess = false;
            for (ApiEndpointConfig ep : endpoints) {
                try {
                    ApiResponse response = pushToEndpoint(ep, queued);
                    if (response.success()) {
                        anySuccess = true;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[Archivist] Offline queue push to {} failed: {}", ep.name, e.getMessage());
                }
            }

            if (!anySuccess) {
                offlineQueue.add(queued);
                break;
            }
        }
    }

    private List<ApiEndpointConfig> getEnabledEndpoints(ArchivistConfig config) {
        if (config == null) return List.of();
        List<ApiEndpointConfig> result = new ArrayList<>(config.apiEndpoints.stream()
                .filter(ep -> ep.enabled && isEndpointConfigured(ep))
                .toList());

        // Add archivist-web as a virtual endpoint when configured
        if (config.archivistWebEnabled
                && config.archivistWebApiKeyEncoded != null
                && !config.archivistWebApiKeyEncoded.isEmpty()) {
            ApiEndpointConfig awEp = new ApiEndpointConfig();
            awEp.id = "archivist-web";
            awEp.name = "archivist-web.net";
            awEp.url = "https://archivist-web.net/api";
            awEp.method = "POST";
            awEp.pushEndpoint = "/push";
            awEp.adapterType = "REST";
            awEp.enabled = true;
            awEp.autoPush = true;
            awEp.confirmedAt = "built-in";
            awEp.authHeadersEncoded = new HashMap<>();
            awEp.authHeadersEncoded.put("X-API-Key", config.archivistWebApiKeyEncoded);
            result.add(awEp);
        }

        return result;
    }

    private static boolean isEndpointConfigured(ApiEndpointConfig ep) {
        if (ep.url == null || ep.url.isBlank()) return false;
        String url = ep.url.trim();
        // Accept URLs with scheme, or bare hostnames (normalizeUrl will prepend https://)
        return url.matches("(?i)^https?://.*") || !url.startsWith("/");
    }

    /**
     * Decodes Base64-encoded auth headers from endpoint config.
     */
    static Map<String, String> decodeHeaders(ApiEndpointConfig ep) {
        Map<String, String> decoded = new LinkedHashMap<>();
        if (ep.authHeadersEncoded == null) return decoded;
        for (Map.Entry<String, String> entry : ep.authHeadersEncoded.entrySet()) {
            try {
                decoded.put(entry.getKey(),
                        new String(Base64.getDecoder().decode(entry.getValue())));
            } catch (Exception e) {
                // Not Base64-encoded, use as-is
                decoded.put(entry.getKey(), entry.getValue());
            }
        }
        return decoded;
    }

    /**
     * Decodes the Base64-encoded reset key.
     */
    static String decodeResetKey(ApiEndpointConfig ep) {
        if (ep.resetKeyEncoded == null || ep.resetKeyEncoded.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(ep.resetKeyEncoded));
        } catch (Exception e) {
            return ep.resetKeyEncoded;
        }
    }

    private synchronized void setStatus(Status newStatus, String message) {
        this.status = newStatus;
        this.statusMessage = message;
    }

    private void postEvent(LogEvent.Type type, String message) {
        if (eventBus != null) {
            eventBus.post(new LogEvent(type, message));
        }
    }

    static String normalizeUrl(String base, String endpoint) {
        if (base == null) base = "";
        base = base.trim();
        // Auto-prepend https:// if no scheme is present
        if (!base.isEmpty() && !base.matches("(?i)^https?://.*")) {
            base = "https://" + base;
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (endpoint == null || endpoint.isEmpty()) return base;
        String ep = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        return base + ep;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
