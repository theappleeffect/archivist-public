package com.archivist.api.automation;

import com.archivist.ArchivistMod;
import com.archivist.config.ArchivistConfig;
import com.archivist.data.EventBus;
import com.archivist.data.JsonLogger;
import com.archivist.data.LogEvent;
import com.archivist.detection.DetectionPipeline;
import com.archivist.data.ServerSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * High-level orchestrator that drives the full multi-server automation loop:
 * connect -> scan (optionally run lobby tasks) -> disconnect -> delay -> next.
 *
 * <p>Simplified phases: IDLE -> CONNECTING -> SCANNING -> DISCONNECTING -> DELAY -> (next or COMPLETED)
 *
 * <p>This is a tick-driven state machine operating above {@link TaskRunner}.
 * Called every client tick from {@code ArchivistMod}.</p>
 */
public final class SequenceHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("archivist");

    public enum Phase {
        IDLE,
        CONNECTING,
        SCANNING,
        DISCONNECTING,
        DELAY,
        COMPLETED,
        ERROR
    }

    public enum LogLevel { INFO, WARN, ERROR, SUCCESS }

    public record AutomationLogEntry(long timestampMs, String message, LogLevel level) {}

    // Dependencies
    private final TaskRunner engine;
    private final EventBus eventBus;
    private final Supplier<DetectionPipeline> pipelineSupplier;
    private final Supplier<ServerSession> sessionSupplier;
    private final ScheduleConfig config;
    private final SequenceState state;

    // Runtime state
    private Phase phase = Phase.IDLE;
    private int ticksInPhase = 0;
    private List<String> currentServerList = Collections.emptyList();
    private volatile boolean joinEventReceived = false;
    private volatile boolean disconnectEventReceived = false;
    private int delayTicks = 0;

    // Retry & failure tracking
    private int currentServerRetries = 0;
    private int consecutiveFailures = 0;
    private boolean retryingSameServer = false;

    // Post-disconnect action
    private Runnable pendingPostDisconnect = null;

    // Lobby task stepped state machine
    private enum LobbyStep { NONE, HOTBAR_SCAN, GUI_SCAN, NPC_FALLBACK, NPC_GUI_SCAN, DONE }
    private LobbyStep lobbyStep = LobbyStep.NONE;
    private SlotScanTask activeSlotScan;
    private ContainerWalkTask activeContainerWalk;
    private int lobbyDoneAtTick = -1; // tick when lobby tasks finished navigating

    // Saved settings (restored when automation stops)
    private boolean savedPassiveGuiDetection;

    // Log for the GUI
    private final List<AutomationLogEntry> log = new ArrayList<>();

    public SequenceHandler(TaskRunner engine, EventBus eventBus,
                                   Supplier<DetectionPipeline> pipelineSupplier,
                                   Supplier<ServerSession> sessionSupplier,
                                   ScheduleConfig config, SequenceState state) {
        this.engine = engine;
        this.eventBus = eventBus;
        this.pipelineSupplier = pipelineSupplier;
        this.sessionSupplier = sessionSupplier;
        this.config = config;
        this.state = state;
    }

    // -- Control methods (called from GUI) --

    public void start() {
        if (config.activeServerList.isEmpty()) {
            addLog("No server list selected", LogLevel.ERROR);
            return;
        }
        currentServerList = AddressListManager.loadList(config.activeServerList);
        if (currentServerList.isEmpty()) {
            addLog("Server list is empty: " + config.activeServerList, LogLevel.ERROR);
            return;
        }

        // Shuffle for stealth if enabled
        if (config.shuffleServerList) {
            currentServerList = new ArrayList<>(currentServerList);
            Collections.shuffle(currentServerList);
            addLog("Server list shuffled", LogLevel.INFO);
        }

        // Save and disable interfering settings
        saveAndDisableSettings();

        state.activeListName = config.activeServerList;
        state.status = SequenceState.RunStatus.RUNNING;
        state.startedAtEpochMs = System.currentTimeMillis();
        state.stoppedAtEpochMs = 0;
        state.lastActivityEpochMs = System.currentTimeMillis();
        consecutiveFailures = 0;
        currentServerRetries = 0;
        retryingSameServer = false;

        addLog("Starting automation (" + currentServerList.size() + " servers)", LogLevel.INFO);
        postEvent("Automation started: " + currentServerList.size() + " servers in " + config.activeServerList);

        advanceToNextServer();
    }

    public void pause() {
        if (phase != Phase.IDLE && phase != Phase.COMPLETED) {
            state.status = SequenceState.RunStatus.PAUSED;
            state.save();
            restoreSettings();
            addLog("Paused", LogLevel.WARN);
            postEvent("Automation paused");
        }
    }

    public void resume() {
        if (state.status == SequenceState.RunStatus.PAUSED) {
            saveAndDisableSettings();
            state.status = SequenceState.RunStatus.RUNNING;
            state.save();
            if (!config.activeServerList.isEmpty()) {
                currentServerList = AddressListManager.loadList(config.activeServerList);
                // Don't reshuffle on resume -- currentIndex would point to wrong server
            }
            addLog("Resumed", LogLevel.INFO);
            postEvent("Automation resumed");
        }
    }

    public void stop() {
        engine.clearTasks();
        state.status = SequenceState.RunStatus.IDLE;
        state.stoppedAtEpochMs = System.currentTimeMillis();
        state.save();
        setPhase(Phase.IDLE);
        restoreSettings();
        addLog("Stopped (progress preserved)", LogLevel.WARN);
        postEvent("Automation stopped");
    }

    /** Skip the current server and advance to the next one. */
    public void skipCurrent() {
        if (phase == Phase.IDLE || phase == Phase.COMPLETED) return;
        engine.clearTasks();
        addLog("Skipping " + state.currentServer, LogLevel.WARN);
        state.markFailed(state.currentServer, "skipped");
        currentServerRetries = 0;
        retryingSameServer = false;

        // Disconnect if connected
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            setPhase(Phase.DISCONNECTING);
            initiateDisconnect(mc);
        } else {
            startDelay();
        }
    }

    // -- Lifecycle callbacks (called from ArchivistMod event handlers) --

    public void onServerJoined() {
        joinEventReceived = true;
    }

    public void onServerDisconnected() {
        disconnectEventReceived = true;
    }

    // -- Main tick --

    public void tick() {
        if (phase == Phase.IDLE || phase == Phase.COMPLETED) return;
        if (state.status == SequenceState.RunStatus.PAUSED) return;

        Minecraft mc = Minecraft.getInstance();

        // UNIVERSAL: Always dismiss DisconnectedScreen during automation.
        // Use mc.disconnect(new TitleScreen(), false) for clean state reset instead of setting JoinMultiplayerScreen.
        if (mc.screen instanceof DisconnectedScreen) {
            String reason = extractDisconnectReason(mc);
            mc.execute(() -> mc.disconnect(new TitleScreen(), false));

            if (phase == Phase.CONNECTING) {
                // Login kick — silent skip, no delay, no failure counter
                addLog("Login rejected: " + reason, LogLevel.WARN);
                state.markFailed(state.currentServer, reason);
                advanceToNextServer();
                return;
            }

            // If we're in an active phase (not already handling disconnect/delay), treat as kick
            if (phase != Phase.DELAY && phase != Phase.DISCONNECTING) {
                engine.clearTasks();
                addLog("Kicked: " + reason, LogLevel.ERROR);
                if ("retry".equals(config.onKickAction)) {
                    handleServerFailure(reason);
                } else {
                    handleServerFailureNoRetry(reason);
                }
                return;
            }
            // If in DELAY or DISCONNECTING, screen is just dismissed, continue normally
        }

        ticksInPhase++;
        state.ticksSincePhaseStart = ticksInPhase;

        switch (phase) {
            case CONNECTING -> tickConnecting(mc);
            case SCANNING -> tickScanning(mc);
            case DISCONNECTING -> tickDisconnecting(mc);
            case DELAY -> tickDelay(mc);
            default -> {}
        }
    }

    private void tickConnecting(Minecraft mc) {
        if (joinEventReceived) {
            joinEventReceived = false;
            lobbyStep = LobbyStep.NONE;
            activeSlotScan = null;
            activeContainerWalk = null;
            lobbyDoneAtTick = -1;
            addLog("Connected to " + state.currentServer, LogLevel.SUCCESS);
            setPhase(Phase.SCANNING);
            return;
        }

        // Timeout — treat like login kick: silent skip, no failure counter, immediate advance
        int timeoutTicks = JitteredTimer.msToTicks(config.connectionTimeoutMs);
        if (ticksInPhase >= timeoutTicks) {
            addLog("Connection timed out: " + state.currentServer, LogLevel.WARN);
            // Force disconnect if partially connected
            if (mc.getConnection() != null) {
                mc.execute(() -> mc.disconnect(new TitleScreen(), false));
            }
            // Dismiss any lingering screen
            if (mc.screen instanceof ConnectScreen || mc.screen instanceof DisconnectedScreen) {
                mc.execute(() -> mc.disconnect(new TitleScreen(), false));
            }
            state.markFailed(state.currentServer, "connection timeout");
            advanceToNextServer();
        }
    }

    /**
     * Unified scanning phase. The settle delay is the first N ticks.
     * After settle, lobby tasks run in conditional steps:
     *   1. Hotbar scan → if GUI opens → scan GUI for game-mode items
     *   2. If no hotbar items → NPC walk → if GUI opens → scan GUI
     *   3. Wait for detection pipeline to complete
     */
    private void tickScanning(Minecraft mc) {
        DetectionPipeline pipeline = pipelineSupplier.get();
        int settleTicks = JitteredTimer.msToTicks(config.settleDelayMs);

        // Wait the full settle delay so all signals (inventory, scoreboard, etc.) have time to fire
        if (lobbyStep == LobbyStep.NONE && ticksInPhase < settleTicks) {
            return;
        }

        // After settle: check confidence (same check the scan overlay toast uses)
        if (lobbyStep == LobbyStep.NONE) {
            double confidence = pipeline.getSessionConfidence().getConfidence();
            boolean lobbyDetected = confidence < config.lobbyThreshold;

            if (lobbyDetected) {
                addLog("Lobby detected (confidence " + String.format("%.0f%%", confidence * 100)
                        + "), starting hotbar scan", LogLevel.INFO);
                activeSlotScan = new SlotScanTask();
                engine.clearTasks();
                engine.addTask(activeSlotScan);
                engine.start();
                lobbyStep = LobbyStep.HOTBAR_SCAN;
            } else {
                addLog("Real server detected (confidence " + String.format("%.0f%%", confidence * 100)
                        + "), waiting for scan", LogLevel.INFO);
                lobbyStep = LobbyStep.DONE;
                lobbyDoneAtTick = -1;
            }
        }

        // Advance lobby steps when engine finishes each task
        if (lobbyStep != LobbyStep.DONE && engine.getState() != TaskRunner.State.RUNNING) {
            advanceLobbyStep(mc);
        }

        // Check if lobby tasks timed out
        if (lobbyStep != LobbyStep.DONE && engine.getState() == TaskRunner.State.RUNNING) {
            int lobbyTimeout = JitteredTimer.msToTicks(config.lobbyTaskTimeoutMs);
            if (ticksInPhase - settleTicks >= lobbyTimeout) {
                addLog("Lobby tasks timed out, aborting", LogLevel.WARN);
                engine.clearTasks();
                lobbyStep = LobbyStep.DONE;
            }
        }

        // Only finish scan after lobby tasks are done — the pipeline may report
        // "scan complete" while we're still in the lobby navigating menus/NPCs.
        if (lobbyStep == LobbyStep.DONE) {
            // Event-driven post-navigate: wait for transfer confirmation or fallback timeout.
            // Do NOT use pipeline.isScanComplete() here — it may be stale from the lobby scan.
            if (lobbyDoneAtTick > 0) {
                double postConf = pipeline.getSessionConfidence().getConfidence();
                boolean transferred = postConf >= config.lobbyThreshold;
                int fallbackTicks = JitteredTimer.msToTicks(config.settleDelayMs);
                boolean fallbackExpired = ticksInPhase >= lobbyDoneAtTick + fallbackTicks;

                if (!transferred && !fallbackExpired) return;
            }

            if (pipeline.isScanComplete()) {
                finishScan(pipeline);
                return;
            }

            // Overall detection timeout
            int timeoutTicks = JitteredTimer.msToTicks(config.detectionTimeoutMs);
            if (ticksInPhase >= timeoutTicks) {
                addLog("Detection timed out, saving what we have", LogLevel.WARN);
                engine.clearTasks();
                finishScan(pipeline);
            }
        }
    }

    /**
     * Advance the lobby task state machine after the current task finishes.
     */
    private void advanceLobbyStep(Minecraft mc) {
        switch (lobbyStep) {
            case HOTBAR_SCAN -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    // GUI opened from hotbar item — scan it for game-mode items
                    addLog("GUI opened, scanning for game-mode items", LogLevel.INFO);
                    activeContainerWalk = new ContainerWalkTask();
                    engine.clearTasks();
                    engine.addTask(activeContainerWalk);
                    engine.start();
                    lobbyStep = LobbyStep.GUI_SCAN;
                } else if (activeSlotScan != null && !activeSlotScan.hasFoundItems() && config.npcFallbackEnabled) {
                    // No hotbar items found — fall back to NPC interaction
                    addLog("No selector items in hotbar, trying NPC interaction", LogLevel.INFO);
                    engine.clearTasks();
                    engine.addTask(new EntityInspectTask());
                    engine.start();
                    lobbyStep = LobbyStep.NPC_FALLBACK;
                } else if (activeSlotScan != null && !activeSlotScan.hasFoundItems()) {
                    // No hotbar items and NPC fallback disabled
                    addLog("No selector items in hotbar, NPC fallback disabled", LogLevel.INFO);
                    lobbyStep = LobbyStep.DONE;
                } else {
                    // Items existed but no GUI opened — nothing more to try
                    addLog("Hotbar items found but no GUI opened", LogLevel.WARN);
                    lobbyStep = LobbyStep.DONE;
                }
            }
            case GUI_SCAN -> {
                if (activeContainerWalk != null && activeContainerWalk.hasFailed()
                        && activeSlotScan != null && activeSlotScan.hasMoreCandidates()) {
                    // GUI had no game-mode match — close and try next hotbar item
                    addLog("No game-mode match in GUI, trying next item", LogLevel.INFO);
                    if (activeSlotScan.resumeFromFailedGui()) {
                        engine.clearTasks();
                        engine.addTask(activeSlotScan);
                        engine.start();
                        lobbyStep = LobbyStep.HOTBAR_SCAN;
                    } else {
                        lobbyStep = LobbyStep.DONE;
                    }
                } else {
                    addLog("GUI scan complete, waiting for transfer", LogLevel.INFO);
                    lobbyDoneAtTick = ticksInPhase;
                    lobbyStep = LobbyStep.DONE;
                }
            }
            case NPC_FALLBACK -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    // NPC opened a GUI — scan it for game-mode items
                    addLog("NPC opened GUI, scanning for game-mode items", LogLevel.INFO);
                    engine.clearTasks();
                    engine.addTask(new ContainerWalkTask());
                    engine.start();
                    lobbyStep = LobbyStep.NPC_GUI_SCAN;
                } else {
                    // NPC click likely transferred directly, or nothing happened
                    addLog("NPC interaction complete, waiting for transfer", LogLevel.INFO);
                    lobbyDoneAtTick = ticksInPhase;
                    lobbyStep = LobbyStep.DONE;
                }
            }
            case NPC_GUI_SCAN -> {
                addLog("NPC GUI scan complete, waiting for transfer", LogLevel.INFO);
                lobbyDoneAtTick = ticksInPhase;
                lobbyStep = LobbyStep.DONE;
            }
            default -> lobbyStep = LobbyStep.DONE;
        }
    }

    /** Complete the scan: save data, mark completed/failed, proceed to disconnect. */
    private void finishScan(DetectionPipeline pipeline) {
        double confidence = pipeline.getSessionConfidence().getConfidence();
        ServerSession session = sessionSupplier.get();
        int pluginCount = session != null ? session.getPlugins().size() : 0;

        if (confidence < config.lobbyThreshold) {
            addLog("Still in lobby (confidence " + String.format("%.0f%%", confidence * 100)
                    + "), marking failed", LogLevel.WARN);
            proceedToDisconnectThen(() -> handleServerFailure("stuck in lobby"));
            return;
        } else {
            addLog("Scan complete: " + pluginCount + " plugins detected", LogLevel.SUCCESS);
            markServerCompleted();
        }
        proceedToDisconnectOrDelay();
    }

    private void tickDisconnecting(Minecraft mc) {
        if (disconnectEventReceived) {
            disconnectEventReceived = false;
            addLog("Disconnected from " + state.currentServer, LogLevel.INFO);
            // Clean up any lingering screen
            if (mc.screen instanceof DisconnectedScreen) {
                mc.execute(() -> mc.disconnect(new TitleScreen(), false));
            }
            if (pendingPostDisconnect != null) {
                Runnable action = pendingPostDisconnect;
                pendingPostDisconnect = null;
                action.run();
            } else {
                startDelay();
            }
            return;
        }

        // Timeout: 60 ticks (3 seconds)
        if (ticksInPhase >= 60) {
            addLog("Disconnect timed out, forcing", LogLevel.WARN);
            disconnectEventReceived = false;
            if (mc.screen instanceof DisconnectedScreen) {
                mc.execute(() -> mc.disconnect(new TitleScreen(), false));
            }
            if (pendingPostDisconnect != null) {
                Runnable action = pendingPostDisconnect;
                pendingPostDisconnect = null;
                action.run();
            } else {
                startDelay();
            }
        }
    }

    private void tickDelay(Minecraft mc) {
        if (ticksInPhase >= delayTicks) {
            if (retryingSameServer) {
                retryingSameServer = false;
                reconnectCurrentServer();
            } else {
                advanceToNextServer();
            }
        }
    }

    // -- Settings save/restore --

    private void saveAndDisableSettings() {
        ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
        savedPassiveGuiDetection = mainConfig.passiveGuiDetection;
        mainConfig.passiveGuiDetection = false;
    }

    private void restoreSettings() {
        ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
        mainConfig.passiveGuiDetection = savedPassiveGuiDetection;
    }

    // -- Failure handling --

    private void handleServerFailure(String reason) {
        consecutiveFailures++;

        // Check retry
        if (currentServerRetries < config.maxRetriesPerServer) {
            currentServerRetries++;
            addLog("Retrying " + state.currentServer + " (attempt " + (currentServerRetries + 1) + "/"
                    + (config.maxRetriesPerServer + 1) + ")", LogLevel.WARN);
            int backoffMs = (int) (config.delayMinMs * Math.pow(config.failureBackoffMultiplier, currentServerRetries));
            delayTicks = JitteredTimer.msToTicks(backoffMs);
            addLog("Backoff delay: " + String.format("%.1f", backoffMs / 1000.0) + "s", LogLevel.INFO);
            retryingSameServer = true;
            setPhase(Phase.DELAY);
            return;
        }

        // Max retries exhausted
        state.markFailed(state.currentServer, reason);
        currentServerRetries = 0;
        retryingSameServer = false;

        if (consecutiveFailures >= config.maxConsecutiveFailures) {
            addLog("Too many consecutive failures (" + consecutiveFailures + "), pausing", LogLevel.ERROR);
            pause();
            return;
        }

        startDelay();
    }

    private void handleServerFailureNoRetry(String reason) {
        state.markFailed(state.currentServer, reason);
        currentServerRetries = 0;
        retryingSameServer = false;
        consecutiveFailures++;

        if (consecutiveFailures >= config.maxConsecutiveFailures) {
            addLog("Too many consecutive failures (" + consecutiveFailures + "), pausing", LogLevel.ERROR);
            pause();
            return;
        }

        startDelay();
    }

    private void markServerCompleted() {
        state.markCompleted(state.currentServer);
        consecutiveFailures = 0;
        currentServerRetries = 0;
        retryingSameServer = false;
    }

    private void proceedToDisconnectThen(Runnable afterDisconnect) {
        if (config.autoDisconnect) {
            pendingPostDisconnect = afterDisconnect;
            setPhase(Phase.DISCONNECTING);
            initiateDisconnect(Minecraft.getInstance());
        } else {
            afterDisconnect.run();
        }
    }

    private void proceedToDisconnectOrDelay() {
        if (config.autoDisconnect) {
            setPhase(Phase.DISCONNECTING);
            initiateDisconnect(Minecraft.getInstance());
        } else {
            startDelay();
        }
    }

    // -- Helpers --

    /** Build a skip predicate for servers that already have log files. */
    private java.util.function.Predicate<String> buildSkipPredicate() {
        if (!config.skipAlreadyLogged) return null;
        Path logsDir = JsonLogger.getLogsDirectory();
        return server -> {
            // Sanitize colon for Windows path safety
            String safe = server.replace(":", "_");
            // Check sanitized name (e.g., play.solarciv.com_42142.json)
            if (Files.exists(logsDir.resolve(safe + ".json"))) return true;
            // Check without port (e.g., play.solarciv.com.json)
            if (server.contains(":")) {
                String noPort = server.substring(0, server.lastIndexOf(':'));
                if (Files.exists(logsDir.resolve(noPort + ".json"))) return true;
            }
            return false;
        };
    }

    private void advanceToNextServer() {
        currentServerRetries = 0;
        retryingSameServer = false;

        String next = state.getNextServer(currentServerList, buildSkipPredicate());
        if (next == null) {
            state.status = SequenceState.RunStatus.COMPLETED;
            state.save();
            setPhase(Phase.COMPLETED);
            restoreSettings();
            int total = currentServerList.size();
            addLog("Automation complete! " + state.getCompletedCount() + "/" + total
                    + " completed, " + state.getFailedCount() + " failed", LogLevel.SUCCESS);
            postEvent("Automation complete");
            return;
        }

        state.currentServer = next;
        // currentIndex already set by getNextServer()
        state.save();

        addLog("Connecting to " + next + " (" + (state.getCompletedCount() + state.getFailedCount() + 1)
                + "/" + currentServerList.size() + ")", LogLevel.INFO);
        initiateConnection(next);
    }

    private void reconnectCurrentServer() {
        String server = state.currentServer;
        if (server == null || server.isEmpty()) {
            addLog("No server to retry, advancing", LogLevel.WARN);
            advanceToNextServer();
            return;
        }
        addLog("Reconnecting to " + server, LogLevel.INFO);
        initiateConnection(server);
    }

    private void initiateConnection(String server) {
        setPhase(Phase.CONNECTING);
        joinEventReceived = false;
        disconnectEventReceived = false;

        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                // Ensure we're on a clean screen first
                if (mc.screen instanceof DisconnectedScreen) {
                    mc.disconnect(new TitleScreen(), false);
                }
                ServerData serverData = new ServerData(server, server, ServerData.Type.OTHER);
                ServerAddress parsed = ServerAddress.parseString(server);
                // Use TitleScreen as parent so failed connections go to a clean state
                ConnectScreen.startConnecting(new TitleScreen(), mc, parsed, serverData, false, null);
            } catch (Exception e) {
                addLog("Failed to connect to " + server + ": " + e.getMessage(), LogLevel.ERROR);
                handleServerFailure("connection error: " + e.getMessage());
            }
        });
    }

    private void initiateDisconnect(Minecraft mc) {
        disconnectEventReceived = false;
        mc.execute(() -> {
            try {
                mc.disconnect(new TitleScreen(), false);  // Minecraft's own clean disconnect
            } catch (Exception e) {
                LOGGER.warn("Error during disconnect", e);
                disconnectEventReceived = true;
            }
        });
    }

    private void startDelay() {
        int delayMs = JitteredTimer.nextDelay(config.delayMinMs, config.delayMaxMs);
        delayTicks = JitteredTimer.msToTicks(delayMs);
        addLog("Waiting " + String.format("%.1f", delayMs / 1000.0) + "s before next server", LogLevel.INFO);
        setPhase(Phase.DELAY);
    }

    private void setPhase(Phase newPhase) {
        this.phase = newPhase;
        this.ticksInPhase = 0;
        state.currentPhase = newPhase.name().toLowerCase();
        state.ticksSincePhaseStart = 0;
    }

    /** Try to extract the disconnect reason text from a DisconnectedScreen. */
    private String extractDisconnectReason(Minecraft mc) {
        if (!(mc.screen instanceof DisconnectedScreen ds)) return "unknown";
        try {
            String narration = ds.getNarrationMessage().getString();
            if (narration != null && !narration.isEmpty()) {
                String title = ds.getTitle().getString();
                if (narration.startsWith(title)) {
                    String reason = narration.substring(title.length()).trim();
                    if (!reason.isEmpty()) return truncate(reason, 120);
                }
                return truncate(narration, 120);
            }
        } catch (Exception ignored) {}
        return "connection rejected";
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private void addLog(String message, LogLevel level) {
        log.add(new AutomationLogEntry(System.currentTimeMillis(), message, level));
        LOGGER.info("[Archivist/Auto] {}", message);
        if (eventBus != null) {
            LogEvent.Type type = switch (level) {
                case ERROR -> LogEvent.Type.ERROR;
                case WARN -> LogEvent.Type.SYSTEM;
                default -> LogEvent.Type.SYSTEM;
            };
            eventBus.post(new LogEvent(type, "[Automation] " + message));
        }
    }

    private void postEvent(String message) {
        // Kept for backward compat, now routes through addLog
        addLog(message, LogLevel.INFO);
    }

    // -- Getters for GUI --

    public Phase getPhase() { return phase; }
    public SequenceState getState() { return state; }
    public ScheduleConfig getConfig() { return config; }
    public TaskRunner getEngine() { return engine; }
    public List<AutomationLogEntry> getLog() { return Collections.unmodifiableList(log); }
    public List<String> getCurrentServerList() { return Collections.unmodifiableList(currentServerList); }

    public String getCurrentServerDisplay() {
        return state.currentServer.isEmpty() ? "\u2014" : state.currentServer;
    }

    public long getElapsedMs() {
        if (state.startedAtEpochMs <= 0) return 0;
        long end = state.stoppedAtEpochMs > 0 ? state.stoppedAtEpochMs : System.currentTimeMillis();
        return end - state.startedAtEpochMs;
    }
}
