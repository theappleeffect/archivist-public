package com.archivist.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ArchivistConfig {

    public boolean enabled = true;
    public String logFolder = "archivist/logs";

    public boolean autoHandleResourcePacks = true;

    public boolean tutorial = true;
    public String activeTheme = "Aurora";
    public boolean gradientsEnabled = true;
    public boolean backgroundGradientEnabled = true;
    public boolean showHudSummary = true;
    public boolean showScanOverlay = true;
    public boolean guiAnimations = true;
    public boolean taskbarSlideAnimation = true;
    public boolean passiveGuiDetection = true;
    public String taskbarPosition = "bottom";
    public String layoutMode = "dynamic";
    public String sidebarPosition = "left";
    public java.util.List<String> sidebarOrder = new java.util.ArrayList<>();

    public List<String> excludedServers = new ArrayList<>();
    public List<String> exceptionServers = new ArrayList<>();

    public String selectedFont = "default";

    public List<ApiEndpointConfig> apiEndpoints = new ArrayList<>();
    public boolean autoUploadOnLog = false;
    public double autoPushMinConfidence = 0.4;

    public String archivistWebApiKeyEncoded = "";
    public boolean archivistWebEnabled = false;
    public boolean archivistWebPromptShown = false;

    public static final class ApiEndpointConfig {
        public String id = UUID.randomUUID().toString();
        public String name = "";
        public String url = "";
        public String method = "POST";
        public String pushEndpoint = "/api/sync";
        public String downloadEndpoint = "/api/download";
        public String resetEndpoint = "/api/reset";
        public Map<String, String> authHeadersEncoded = new HashMap<>();
        public String resetKeyEncoded = "";
        public String adapterType = "REST";
        public boolean autoPush = false;
        public boolean enabled = true;
        public String confirmedAt = null;
    }

    public boolean isExcluded(String address) {
        if (address == null || address.isEmpty()) return false;
        String lower = address.toLowerCase(java.util.Locale.ROOT);
        for (String s : excludedServers) {
            if (lower.equals(s.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }

    public boolean isException(String address) {
        if (address == null || address.isEmpty()) return false;
        String lower = address.toLowerCase(java.util.Locale.ROOT);
        for (String s : exceptionServers) {
            if (lower.equals(s.toLowerCase(java.util.Locale.ROOT))) return true;
        }
        return false;
    }
}
