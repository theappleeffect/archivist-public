package com.archivist.api;

import com.archivist.util.ArchivistExecutor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Implements the device code flow for automatic API key provisioning
 * via archivist-web.net. All HTTP runs on {@link ArchivistExecutor}.
 *
 * <p>State transitions: IDLE → REQUESTING → AWAITING_USER → POLLING → COMPLETED / EXPIRED / ERROR</p>
 */
public final class DeviceCodeFlow {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    private static final String DEVICE_ENDPOINT = "https://archivist-web.net/api/auth/device";
    private static final String POLL_ENDPOINT = "https://archivist-web.net/api/auth/device/poll";
    private static final long POLL_INTERVAL_MS = 5000;
    private static final long BACKOFF_INTERVAL_MS = 10000;

    public enum State {
        IDLE, REQUESTING, AWAITING_USER, POLLING, COMPLETED, EXPIRED, ERROR
    }

    private volatile State state = State.IDLE;
    private volatile String userCode;
    private volatile String verificationUrl;
    private volatile String deviceCode;
    private volatile String apiKey;
    private volatile String errorMessage;
    private volatile boolean cancelled = false;

    private final ApiClient client = new ApiClient();
    private final Consumer<State> onStateChanged;

    /**
     * @param onStateChanged called on the render thread whenever state changes
     */
    public DeviceCodeFlow(Consumer<State> onStateChanged) {
        this.onStateChanged = onStateChanged;
    }

    /**
     * Starts the device code flow. Requests a device code, then begins polling.
     * Must only be called once.
     */
    public void start() {
        if (state != State.IDLE) return;
        setState(State.REQUESTING);

        ArchivistExecutor.execute(() -> {
            try {
                // Step 1: Request device code
                ApiResponse response = client.post(DEVICE_ENDPOINT, "{}", Map.of());

                if (cancelled) return;

                if (response.statusCode() == 429) {
                    setError("Rate limited. Please try again later.");
                    return;
                }

                if (!response.success()) {
                    String detail = parseErrorDetail(response.body());
                    setError("Failed to request device code (HTTP " + response.statusCode() + ")"
                            + (detail != null ? "\n" + detail : ""));
                    return;
                }

                JsonObject data = JsonParser.parseString(response.body()).getAsJsonObject();
                deviceCode = data.get("device_code").getAsString();
                userCode = data.get("user_code").getAsString();
                verificationUrl = data.get("verification_url").getAsString();
                int expiresIn = data.get("expires_in").getAsInt();

                setState(State.AWAITING_USER);

                // Step 2: Poll for completion
                long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
                long interval = POLL_INTERVAL_MS;

                while (!cancelled && System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval);
                    if (cancelled) return;

                    setState(State.POLLING);

                    String pollBody = "{\"device_code\":\"" + deviceCode + "\"}";
                    ApiResponse pollResponse = client.post(POLL_ENDPOINT, pollBody, Map.of());

                    if (cancelled) return;

                    if (pollResponse.statusCode() == 429) {
                        // Back off on rate limit
                        interval = BACKOFF_INTERVAL_MS;
                        setState(State.AWAITING_USER);
                        continue;
                    }

                    // Reset interval after successful request
                    interval = POLL_INTERVAL_MS;

                    if (!pollResponse.success()) {
                        // Transient error — keep polling
                        LOGGER.warn("[Archivist] Device poll error: HTTP {}", pollResponse.statusCode());
                        setState(State.AWAITING_USER);
                        continue;
                    }

                    JsonObject pollData = JsonParser.parseString(pollResponse.body()).getAsJsonObject();
                    String status = pollData.get("status").getAsString();

                    if ("completed".equals(status)) {
                        apiKey = pollData.get("api_key").getAsString();
                        setState(State.COMPLETED);
                        return;
                    }

                    if ("expired".equals(status)) {
                        setState(State.EXPIRED);
                        return;
                    }

                    // "pending" — continue polling
                    setState(State.AWAITING_USER);
                }

                // Timed out
                if (!cancelled && state != State.COMPLETED) {
                    setState(State.EXPIRED);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!cancelled) setError("Interrupted");
            } catch (Exception e) {
                LOGGER.warn("[Archivist] Device code flow error: {}", e.getMessage());
                if (!cancelled) setError(e.getMessage());
            }
        });
    }

    /**
     * Cancels the flow. Safe to call from any thread.
     */
    public void cancel() {
        cancelled = true;
    }

    private void setState(State newState) {
        state = newState;
        if (onStateChanged != null) {
            Minecraft.getInstance().execute(() -> onStateChanged.accept(newState));
        }
    }

    private void setError(String message) {
        errorMessage = message;
        setState(State.ERROR);
    }

    private static String parseErrorDetail(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("detail") && !obj.get("detail").isJsonNull()) {
                return obj.get("detail").getAsString();
            }
            if (obj.has("error") && !obj.get("error").isJsonNull()) {
                return obj.get("error").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    public State getState() { return state; }
    public String getUserCode() { return userCode; }
    public String getVerificationUrl() { return verificationUrl; }
    public String getApiKey() { return apiKey; }
    public String getErrorMessage() { return errorMessage; }
    public boolean isCancelled() { return cancelled; }
}
