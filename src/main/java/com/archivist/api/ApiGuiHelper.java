package com.archivist.api;

import com.archivist.config.ArchivistConfig;
import com.archivist.config.ArchivistConfig.ApiEndpointConfig;
import com.archivist.config.ConfigManager;
import com.archivist.data.EventBus;
import com.archivist.data.LogEvent;
import com.archivist.data.ServerLogData;
import com.archivist.data.ServerSession;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.widgets.*;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;

/**
 * Builds all API tab GUI widgets. Lives inside the api package to keep
 * GUI logic contained. ArchivistScreen just calls build() and adds the result.
 */
public final class ApiGuiHelper {

    private static final List<String> ADAPTER_TYPES = List.of("REST", "Discord");

    // Tracks the currently selected adapter type in the Add form (survives rebuilds within session)
    private static String selectedAdapterType = "REST";

    // Active device code flow for Link Account (survives rebuilds within session)
    private static DeviceCodeFlow activeDeviceFlow;
    // Last error/success message from a device code flow (survives rebuilds within session)
    private static String flowResultMessage;
    private static boolean flowResultSuccess;

    private ApiGuiHelper() {}

    public static void build(Panel panel, ArchivistConfig config, ServerSession session,
                             ApiSyncManager syncManager, EventBus eventBus, Runnable rebuildCallback) {
        ColorScheme cs = ColorScheme.get();

        if (syncManager == null) {
            panel.addChild(new Label(0, 0, 200, "API system not initialized", cs.textSecondary()));
            return;
        }

        // ── Status ──
        String statusText = syncManager.getStatus().name() + ": " + syncManager.getStatusMessage();
        int statusColor = switch (syncManager.getStatus()) {
            case CONNECTED -> 0xFF44BB44;
            case UPLOADING -> 0xFF4488DD;
            case ERROR -> 0xFFBB4444;
            default -> cs.textSecondary();
        };
        panel.addChild(new Label(0, 0, 200, statusText, statusColor));

        int queueSize = syncManager.getQueueSize();
        if (queueSize > 0) {
            panel.addChild(new Label(0, 0, 200, "Queue: " + queueSize + " pending", 0xFFBBBB44));
        }

        panel.addChild(new Label(0, 0, 200, ""));

        // ── Archivist-web ──
        panel.addChild(new Label(0, 0, 200, "\u258C archivist-web.net", cs.accent()));

        boolean awHasKey = config.archivistWebApiKeyEncoded != null
                && !config.archivistWebApiKeyEncoded.isEmpty();

        // Check if a device code flow is active
        boolean flowActive = activeDeviceFlow != null
                && activeDeviceFlow.getState() != DeviceCodeFlow.State.COMPLETED
                && activeDeviceFlow.getState() != DeviceCodeFlow.State.EXPIRED
                && activeDeviceFlow.getState() != DeviceCodeFlow.State.ERROR
                && !activeDeviceFlow.isCancelled();

        if (awHasKey && !flowActive) {
            // Key is configured — show status and controls
            String awStatus = config.archivistWebEnabled ? "\u25CF Connected" : "\u25CB Disabled";
            int awStatusColor = config.archivistWebEnabled ? 0xFF44BB44 : cs.textSecondary();
            panel.addChild(new Label(0, 0, 200, awStatus, awStatusColor));

            panel.addChild(new CheckBox(0, 0, 200, "Enabled", config.archivistWebEnabled, v -> {
                config.archivistWebEnabled = v;
                ConfigManager.save(config);
                rebuildCallback.run();
            }));

            panel.addChild(new Button(0, 0, 100, "Remove Key", () -> {
                config.archivistWebApiKeyEncoded = "";
                config.archivistWebEnabled = false;
                ConfigManager.save(config);
                rebuildCallback.run();
            }));
        } else if (flowActive) {
            // Device code flow in progress — show code and status
            String code = activeDeviceFlow.getUserCode();
            String url = activeDeviceFlow.getVerificationUrl();

            if (code != null) {
                panel.addChild(new Label(0, 0, 200, "Go to: " + (url != null ? url : "archivist-web.net/link"), cs.textSecondary()));
                panel.addChild(new Label(0, 0, 200, "Code: " + code, cs.accent()));
                panel.addChild(new Label(0, 0, 200, "Waiting for authorization...", cs.textSecondary()));

                panel.addChild(new Button(0, 0, 100, "Open Browser", () -> {
                    String browseUrl = activeDeviceFlow != null && activeDeviceFlow.getVerificationUrl() != null
                            ? activeDeviceFlow.getVerificationUrl() : "https://archivist-web.net/link";
                    String userCode = activeDeviceFlow != null ? activeDeviceFlow.getUserCode() : null;
                    if (userCode != null) browseUrl += "?code=" + userCode;
                    openUrl(browseUrl);
                }));
            } else {
                panel.addChild(new Label(0, 0, 200, "Requesting code...", cs.textSecondary()));
            }

            panel.addChild(new Button(0, 0, 80, "Cancel", () -> {
                if (activeDeviceFlow != null) activeDeviceFlow.cancel();
                activeDeviceFlow = null;
                flowResultMessage = null;
                rebuildCallback.run();
            }));
        } else {
            // No key, no active flow — show input and link button
            panel.addChild(new Label(0, 0, 200, "\u25CB No API key", cs.textSecondary()));

            // Show result from previous flow attempt
            if (flowResultMessage != null) {
                int resultColor = flowResultSuccess ? 0xFF44BB44 : 0xFFBB4444;
                panel.addChild(new Label(0, 0, 200, flowResultMessage, resultColor));
            }

            panel.addChild(new Button(0, 0, 100, "Connect", () -> {
                flowResultMessage = null;
                activeDeviceFlow = new DeviceCodeFlow(state -> {
                    if (state == DeviceCodeFlow.State.COMPLETED && activeDeviceFlow != null) {
                        String apiKey = activeDeviceFlow.getApiKey();
                        if (apiKey != null && !apiKey.isEmpty()) {
                            config.archivistWebApiKeyEncoded = Base64.getEncoder().encodeToString(apiKey.getBytes());
                            config.archivistWebEnabled = true;
                            config.archivistWebPromptShown = true;
                            ConfigManager.save(config);
                            flowResultMessage = "Connected!";
                            flowResultSuccess = true;
                            eventBus.post(new LogEvent(LogEvent.Type.DB_SYNC, "archivist-web: linked successfully"));
                        }
                        activeDeviceFlow = null;
                    } else if (state == DeviceCodeFlow.State.EXPIRED) {
                        flowResultMessage = "Code expired. Try again.";
                        flowResultSuccess = false;
                        eventBus.post(new LogEvent(LogEvent.Type.ERROR, "archivist-web: device code expired"));
                        activeDeviceFlow = null;
                    } else if (state == DeviceCodeFlow.State.ERROR) {
                        String err = activeDeviceFlow != null ? activeDeviceFlow.getErrorMessage() : null;
                        flowResultMessage = err != null ? err : "Connection failed";
                        flowResultSuccess = false;
                        eventBus.post(new LogEvent(LogEvent.Type.ERROR, "archivist-web: " + flowResultMessage));
                        activeDeviceFlow = null;
                    }
                    rebuildCallback.run();
                });
                activeDeviceFlow.start();
                rebuildCallback.run();
            }));

        }

        panel.addChild(new Label(0, 0, 200, ""));

        // ── Endpoint list (right-click for options) ──
        panel.addChild(new Label(0, 0, 200, "Endpoints (right-click for options):", cs.accent()));

        List<ApiEndpointConfig> endpoints = config.apiEndpoints;
        int listHeight = Math.max(28, endpoints.size() * 12 + 4);
        ScrollableList endpointList = new ScrollableList(0, 0, 200, listHeight);

        for (ApiEndpointConfig ep : endpoints) {
            String dot = ep.enabled ? "\u25CF " : "\u25CB ";
            String type = ep.adapterType != null ? ep.adapterType : "REST";
            boolean insecure = "REST".equalsIgnoreCase(type) && ep.url != null && ApiClient.isInsecureUrl(ep.url);
            String warn = insecure ? " \u26A0" : "";
            String display = dot + ep.name + " [" + type + "]" + warn;
            int color = ep.enabled ? cs.textPrimary() : cs.textSecondary();
            endpointList.addItem(new ScrollableList.ListItem(display, color, ep));
        }

        endpointList.setOnSelect(idx -> {
            var items = endpointList.getItems();
            if (idx < 0 || idx >= items.size()) return;
            var ep = (ApiEndpointConfig) items.get(idx).data;
            if (ep == null) return;
            if (!ep.enabled && ep.confirmedAt == null) ep.confirmedAt = Instant.now().toString();
            ep.enabled = !ep.enabled;
            ConfigManager.save(config);
            rebuildCallback.run();
        });

        endpointList.setOnRightClick((item, index, mx, my) -> {
            var ep = (ApiEndpointConfig) item.data;
            if (ep == null) return;
            ContextMenu menu = new ContextMenu(mx, my);
            menu.addItem(ep.enabled ? "Disable" : "Enable", () -> {
                if (!ep.enabled && ep.confirmedAt == null) ep.confirmedAt = Instant.now().toString();
                ep.enabled = !ep.enabled;
                ConfigManager.save(config);
                rebuildCallback.run();
            });
            menu.addItem("Test", () -> syncManager.testConnection(ep));
            menu.addItem("Delete", () -> {
                config.apiEndpoints.remove(ep);
                ConfigManager.save(config);
                rebuildCallback.run();
            });
            PopupLayer.open(menu, () -> new int[]{mx, my}, null);
        });

        panel.addChild(endpointList);

        // ── Add Endpoint ──
        panel.addChild(new Label(0, 0, 200, ""));
        panel.addChild(new Label(0, 0, 200, "\u258C Add Endpoint", cs.accent()));

        // Adapter type dropdown
        Dropdown typeDropdown = new Dropdown(0, 0, 200, "",
                ADAPTER_TYPES, selectedAdapterType, v -> {
            selectedAdapterType = v;
            rebuildCallback.run(); // rebuild to show correct fields
        });
        panel.addChild(typeDropdown);

        // Common: Name
        TextField nameField = new TextField(0, 0, 200, "Endpoint name");
        panel.addChild(nameField);

        // Type-specific fields
        String type = selectedAdapterType.toUpperCase();
        TextField urlField;
        TextField authField = null;

        switch (type) {
            case "DISCORD" -> {
                urlField = new TextField(0, 0, 200, "Webhook URL");
                panel.addChild(urlField);
                authField = new TextField(0, 0, 200, "Bot token (optional)");
                panel.addChild(authField);
            }
            default -> { // REST
                urlField = new TextField(0, 0, 200, "Base URL");
                panel.addChild(urlField);
                authField = new TextField(0, 0, 200, "Auth token (optional)");
                panel.addChild(authField);
            }
        }

        CheckBox autoPushCb = new CheckBox(0, 0, 200, "Auto-push on disconnect", false, v -> {});
        panel.addChild(autoPushCb);

        final TextField capturedAuth = authField;
        panel.addChild(new Button(0, 0, 150, "Add Endpoint", () -> {
            String name = nameField.getText().trim();
            String url = urlField.getText().trim();
            if (name.isEmpty() || url.isEmpty()) return;

            // Auto-prepend https:// if no scheme provided
            if (!url.matches("(?i)^https?://.*")) {
                url = "https://" + url;
            }

            ApiEndpointConfig ep = new ApiEndpointConfig();
            ep.name = name;
            ep.url = url;
            ep.adapterType = selectedAdapterType;
            ep.autoPush = autoPushCb.isChecked();
            ep.confirmedAt = Instant.now().toString();

            if (capturedAuth != null) {
                String auth = capturedAuth.getText().trim();
                if (!auth.isEmpty()) {
                    ep.authHeadersEncoded = new HashMap<>();
                    // Send as X-API-Key header (preferred by Archivist API)
                    ep.authHeadersEncoded.put("X-API-Key",
                            Base64.getEncoder().encodeToString(auth.getBytes()));
                }
            }

            config.apiEndpoints.add(ep);
            ConfigManager.save(config);
            rebuildCallback.run();
        }));

        panel.addChild(new Label(0, 0, 200, ""));

        // ── Global actions ──
        panel.addChild(new Label(0, 0, 200, "\u258C Actions", cs.accent()));

        panel.addChild(new CheckBox(0, 0, 200, "Auto-upload when logging",
                config.autoUploadOnLog,
                v -> { config.autoUploadOnLog = v; ConfigManager.save(config); }));

        panel.addChild(new Button(0, 0, 80, "Push Now", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            syncManager.pushData(data, resp -> {
                if (resp.success()) {
                    eventBus.post(new LogEvent(LogEvent.Type.DB_SYNC, "Manual push complete"));
                }
            });
        }));

        if (queueSize > 0) {
            panel.addChild(new Button(0, 0, 100, "Flush Queue (" + queueSize + ")", () -> {
                syncManager.pushAll();
            }));
        }

        panel.addChild(new Button(0, 0, 80, "Download", () -> {
            syncManager.downloadAll(resp -> {
                if (resp.success()) {
                    eventBus.post(new LogEvent(LogEvent.Type.DB_SYNC, "Download complete"));
                }
            });
        }));

        panel.addChild(new Label(0, 0, 200, ""));
        panel.addChild(new Label(0, 0, 200, "Ctrl+Shift+D: emergency disconnect", cs.textSecondary()));
    }

    public static void openUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception ignored) {}
    }
}
