package com.archivist.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Unified configuration for the Archivist mod.
 */
public final class ArchivistConfig {

    // Core
    public boolean enabled = true;
    public String logFolder = "archivist/logs";

    // Scraper
    public boolean autoHandleResourcePacks = true;

    // GUI
    public boolean tutorial = true;
    public String activeTheme = "Aurora";
    public boolean gradientsEnabled = true;
    public boolean backgroundGradientEnabled = true;
    public boolean showHudSummary = true;
    public boolean showScanOverlay = true;
    public boolean guiAnimations = true;
    public boolean taskbarSlideAnimation = true;
    public boolean passiveGuiDetection = true;
    public String taskbarPosition = "bottom"; // "bottom", "top", "left"
    public String layoutMode = "dynamic"; // "windows" or "dynamic"
    public String sidebarPosition = "left"; // "left" or "right" (dynamic mode only)
    public java.util.List<String> sidebarOrder = new java.util.ArrayList<>();

    // API
    public List<ApiEndpointConfig> apiEndpoints = new ArrayList<>();
    public boolean autoUploadOnLog = false;
    public double autoPushMinConfidence = 0.4;

    // Archivist-web integration
    public String archivistWebApiKeyEncoded = "";   // Base64-encoded
    public boolean archivistWebEnabled = false;
    public boolean archivistWebPromptShown = false;  // prevents repeat popup

    /**
     * Configuration for a single API endpoint.
     */
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
        public String adapterType = "REST"; // REST, DISCORD, MONGODB, POSTGRES, SQLITE, CUSTOM
        public boolean autoPush = false;
        public boolean enabled = true;
        public String confirmedAt = null; // ISO timestamp of first opt-in confirmation
    }
}
