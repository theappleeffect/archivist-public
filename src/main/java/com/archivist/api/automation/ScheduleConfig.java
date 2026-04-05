package com.archivist.api.automation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent configuration for the automation feature.
 * Stored at {@code .minecraft/archivist/automation/automation_config.json}.
 */
public final class ScheduleConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Name of the selected .txt server list file (without extension). */
    public String activeServerList = "";

    /** Proxy profile ID from ProxyManager, or empty for no proxy. */
    public String proxyProfileId = "";

    /** Minimum random delay between servers (ms). */
    public int delayMinMs = 500;

    /** Maximum random delay between servers (ms). */
    public int delayMaxMs = 2000;

    /** Per-server connection timeout (ms). */
    public int connectionTimeoutMs = 5000;

    /** Max time to wait for detection pipeline to complete (ms). */
    public int detectionTimeoutMs = 20000;

    /** Whether to auto-disconnect after capture. */
    public boolean autoDisconnect = true;

    // ── Retry & Failure ──

    /** Max connection retries per server before marking failed. */
    public int maxRetriesPerServer = 10;

    /** Max consecutive failures before auto-pausing. */
    public int maxConsecutiveFailures = 5;

    /** Multiply delay by this factor after each consecutive failure (1.0 = no backoff). */
    public double failureBackoffMultiplier = 1.5;

    // ── Lobby Task Timing ──

    /** Max time for all lobby tasks combined (ms). */
    public int lobbyTaskTimeoutMs = 15000;

    /** Time to wait after join before starting lobby tasks (ms). Fallback — event-driven check proceeds earlier. */
    public int settleDelayMs = 3000;

    // ── Kick/Disconnect Handling ──

    /** What to do when kicked during scan: "skip" or "retry". */
    public String onKickAction = "skip";

    // ── Server List ──

    /** Shuffle server list order for stealth. */
    public boolean shuffleServerList = false;

    /** Skip servers that already have a log file in archivist/logs/. */
    public boolean skipAlreadyLogged = false;

    // ── Detection ──

    /** Lobby confidence threshold (below this = lobby detected). */
    public double lobbyThreshold = 0.4;

    /** Whether to use NPC fallback if hotbar/container methods fail. */
    public boolean npcFallbackEnabled = true;

    // ── Active GUI Scanning ──

    /** Whether to run active GUI scan commands during automation. */
    public boolean activeGuiScanEnabled = false;

    /** Commands to send for active GUI scanning (without leading slash). */
    public java.util.List<String> activeGuiCommands = java.util.List.of("ah", "shop", "ec", "menu");

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("archivist").resolve("automation").resolve("automation_config.json");
    }

    public static synchronized ScheduleConfig load() {
        Path path = getConfigPath();
        if (!Files.exists(path)) {
            ScheduleConfig defaults = new ScheduleConfig();
            save(defaults);
            return defaults;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            ScheduleConfig config = GSON.fromJson(json, ScheduleConfig.class);
            return config != null ? config : new ScheduleConfig();
        } catch (IOException e) {
            LOGGER.error("Failed to load automation config, using defaults", e);
            return new ScheduleConfig();
        }
    }

    public static synchronized void save(ScheduleConfig config) {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save automation config", e);
        }
    }
}
