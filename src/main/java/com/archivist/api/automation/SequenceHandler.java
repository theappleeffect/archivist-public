package com.archivist.api.automation;

import com.archivist.ArchivistMod;
import com.archivist.config.ArchivistConfig;
import com.archivist.data.EventBus;
import com.archivist.data.JsonLogger;
import com.archivist.data.LogEvent;
import com.archivist.detection.DetectionPipeline;
import com.archivist.detection.SessionConfidence;
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
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

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

    private final TaskRunner engine;
    private final EventBus eventBus;
    private final Supplier<DetectionPipeline> pipelineSupplier;
    private final Supplier<ServerSession> sessionSupplier;
    private final ScheduleConfig config;
    private final SequenceState state;

    private Phase phase = Phase.IDLE;
    private int ticksInPhase = 0;
    private List<String> currentServerList = Collections.emptyList();
    private volatile boolean joinEventReceived = false;
    private volatile boolean disconnectEventReceived = false;
    private int delayTicks = 0;

    private int currentServerRetries = 0;
    private int consecutiveFailures = 0;
    private boolean retryingSameServer = false;

    private Runnable pendingPostDisconnect = null;

    private enum LobbyStep { NONE, HOTBAR_SCAN, GUI_SCAN, NPC_ITERATION, DONE, ACTIVE_GUI_SCAN }
    private LobbyStep lobbyStep = LobbyStep.NONE;
    private SlotScanTask activeSlotScan;
    private ContainerWalkTask activeContainerWalk;
    private int lobbyDoneAtTick = -1;

    private int transferDetectedAtTick = -1;
    private static final int DWELL_TICKS = 45;

    private EntityInspectTask activeNpcTask;
    private ContainerWalkTask npcContainerWalk;
    private enum NpcSubStep { INSPECT, GUI_SCAN }
    private NpcSubStep npcSubStep = NpcSubStep.INSPECT;
    private static final int MAX_NPC_ATTEMPTS = 5;
    private int npcAttemptCount = 0;

    private boolean savedPassiveGuiDetection;
    private ActiveGuiScanTask activeGuiScan;
    private boolean guiScanCompleted = false;
    private boolean versionProbeSent = false;

    private static final Set<String> AUTH_CAPTCHA_KEYWORDS = Set.of(
            "captcha", "solve captcha",
            "/login", "/register", "/l ", "/reg ",
            "/auth", "/premium", "/changepassword",
            "login to play", "register to play",
            "log in", "sign in", "sign up"
    );

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

        if (config.shuffleServerList) {
            currentServerList = new ArrayList<>(currentServerList);
            Collections.shuffle(currentServerList);
            addLog("Server list shuffled", LogLevel.INFO);
        }

        saveAndDisableSettings();

        state.currentIndex = 0;
        state.completedServers.clear();
        state.failedServers.clear();

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
            }
            addLog("Resumed", LogLevel.INFO);
            postEvent("Automation resumed");
        }
    }

    public void stop() {
        engine.clearTasks();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            mc.execute(() -> mc.disconnect(new TitleScreen(), false));
        }
        state.status = SequenceState.RunStatus.IDLE;
        state.stoppedAtEpochMs = System.currentTimeMillis();
        state.save();
        setPhase(Phase.IDLE);
        restoreSettings();
        addLog("Stopped (progress preserved)", LogLevel.WARN);
        postEvent("Automation stopped");
    }

    public void skipCurrent() {
        if (phase == Phase.IDLE || phase == Phase.COMPLETED) return;
        engine.clearTasks();
        addLog("Skipping " + state.currentServer, LogLevel.WARN);
        state.markFailed(state.currentServer, "skipped");
        currentServerRetries = 0;
        retryingSameServer = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            setPhase(Phase.DISCONNECTING);
            initiateDisconnect(mc);
        } else {
            startDelay();
        }
    }

    public void onServerJoined() {
        joinEventReceived = true;
    }

    public void onServerDisconnected() {
        disconnectEventReceived = true;
    }

    public void tick() {
        if (phase == Phase.IDLE || phase == Phase.COMPLETED) return;
        if (state.status == SequenceState.RunStatus.PAUSED) return;

        Minecraft mc = Minecraft.getInstance();

        if (mc.screen instanceof DisconnectedScreen) {
            String reason = extractDisconnectReason(mc);
            mc.execute(() -> mc.disconnect(new TitleScreen(), false));

            if (phase == Phase.CONNECTING) {
                addLog("Login rejected: " + reason, LogLevel.WARN);
                String reasonLower = reason.toLowerCase(java.util.Locale.ROOT);
                boolean shouldSkip = reasonLower.contains("vpn") || reasonLower.contains("proxy")
                        || reasonLower.contains("suspicious") || reasonLower.contains("blocked")
                        || reasonLower.contains("blacklist") || reasonLower.contains("banned")
                        || reasonLower.contains("unusual activity") || reasonLower.contains("already connected")
                        || reasonLower.contains("ratelimit") || reasonLower.contains("rate limit")
                        || reasonLower.contains("malicious") || reasonLower.contains("flagged");
                if (shouldSkip) {
                    addLog("Auto-skipping: " + reason.substring(0, Math.min(reason.length(), 60)), LogLevel.WARN);
                }
                state.markFailed(state.currentServer, reason);
                setPhase(Phase.DELAY);
                return;
            }

            if (phase != Phase.DELAY && phase != Phase.DISCONNECTING) {
                engine.clearTasks();
                addLog("Kicked: " + reason, LogLevel.ERROR);
                String reasonLower = reason.toLowerCase(java.util.Locale.ROOT);
                boolean vpnOrSuspicious = reasonLower.contains("vpn") || reasonLower.contains("proxy")
                        || reasonLower.contains("suspicious") || reasonLower.contains("blocked")
                        || reasonLower.contains("blacklist") || reasonLower.contains("banned")
                        || reasonLower.contains("unusual activity") || reasonLower.contains("already connected");
                if (vpnOrSuspicious || !"retry".equals(config.onKickAction)) {
                    if (vpnOrSuspicious) addLog("VPN/suspicious kick detected, skipping server", LogLevel.WARN);
                    handleServerFailureNoRetry(reason);
                } else {
                    handleServerFailure(reason);
                }
                return;
            }
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
            transferDetectedAtTick = -1;
            activeNpcTask = null;
            npcContainerWalk = null;
            npcSubStep = NpcSubStep.INSPECT;
            npcAttemptCount = 0;
            activeGuiScan = null;
            guiScanCompleted = false;
            versionProbeSent = false;
            addLog("Connected to " + state.currentServer, LogLevel.SUCCESS);
            setPhase(Phase.SCANNING);
            return;
        }

        int timeoutTicks = JitteredTimer.msToTicks(config.connectionTimeoutMs);
        if (ticksInPhase >= timeoutTicks) {
            addLog("Connection timed out: " + state.currentServer, LogLevel.WARN);
            if (mc.getConnection() != null) {
                mc.execute(() -> mc.disconnect(new TitleScreen(), false));
            }
            if (mc.screen instanceof ConnectScreen || mc.screen instanceof DisconnectedScreen) {
                mc.execute(() -> mc.disconnect(new TitleScreen(), false));
            }
            state.markFailed(state.currentServer, "connection timeout");
            advanceToNextServer();
        }
    }

    private void tickScanning(Minecraft mc) {
        DetectionPipeline pipeline = pipelineSupplier.get();
        int settleTicks = JitteredTimer.msToTicks(config.settleDelayMs);

        if (lobbyStep == LobbyStep.NONE && ticksInPhase < settleTicks) {
            SessionConfidence sc = pipeline.getSessionConfidence();
            if (sc.isForceLobby() && !sc.isForceReal()) {
                addLog("Lobby item detected early, skipping settle delay", LogLevel.INFO);
            } else if (pipeline.isScanComplete() && sc.isInventoryChecked() && ticksInPhase >= 20) {
                addLog("Scan and inventory ready at tick " + ticksInPhase + ", proceeding early", LogLevel.INFO);
            } else {
                return;
            }
        }

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

        if (lobbyStep != LobbyStep.DONE && engine.getState() != TaskRunner.State.RUNNING) {
            advanceLobbyStep(mc);
        }

        if (lobbyStep != LobbyStep.DONE && engine.getState() == TaskRunner.State.RUNNING) {
            int lobbyTimeout = JitteredTimer.msToTicks(config.lobbyTaskTimeoutMs);
            if (ticksInPhase - settleTicks >= lobbyTimeout) {
                addLog("Lobby tasks timed out, aborting", LogLevel.WARN);
                engine.clearTasks();
                lobbyStep = LobbyStep.DONE;
            }
        }

        if (lobbyStep == LobbyStep.DONE) {
            if (lobbyDoneAtTick > 0) {
                double postConf = pipeline.getSessionConfidence().getConfidence();
                boolean transferred = postConf >= config.lobbyThreshold;
                int fallbackTicks = JitteredTimer.msToTicks(config.settleDelayMs);
                boolean fallbackExpired = ticksInPhase >= lobbyDoneAtTick + fallbackTicks;

                if (transferred && transferDetectedAtTick < 0) {
                    transferDetectedAtTick = ticksInPhase;
                    addLog("Transfer detected, dwelling for 3s", LogLevel.INFO);
                }

                if (transferDetectedAtTick > 0) {
                    if (ticksInPhase < transferDetectedAtTick + DWELL_TICKS) return;
                } else if (!fallbackExpired) {
                    return;
                }
            }

            if (pipeline.isScanComplete() && !guiScanCompleted && config.activeGuiScanEnabled
                    && !config.activeGuiCommands.isEmpty()
                    && pipeline.getSessionConfidence().getConfidence() >= config.lobbyThreshold) {
                addLog("Starting active GUI scan (" + config.activeGuiCommands.size() + " commands)", LogLevel.INFO);
                activeGuiScan = new ActiveGuiScanTask(config.activeGuiCommands);
                engine.clearTasks();
                engine.addTask(activeGuiScan);
                engine.start();
                guiScanCompleted = true;
                lobbyStep = LobbyStep.ACTIVE_GUI_SCAN;
                return;
            }

            if (pipeline.isScanComplete()) {
                if (!versionProbeSent) {
                    String versionCmd = pipeline.getVersionCommand();
                    if (versionCmd != null && mc.getConnection() != null) {
                        addLog("Sending version probe: /" + versionCmd, LogLevel.INFO);
                        pipeline.startVersionProbe();
                        mc.getConnection().sendCommand(versionCmd);
                    }
                    versionProbeSent = true;
                }
                finishScan(pipeline);
                return;
            }

            int timeoutTicks = JitteredTimer.msToTicks(config.detectionTimeoutMs);
            if (ticksInPhase >= timeoutTicks) {
                addLog("Detection timed out, saving what we have", LogLevel.WARN);
                engine.clearTasks();
                finishScan(pipeline);
            }
        }

        if (lobbyStep == LobbyStep.ACTIVE_GUI_SCAN && engine.getState() != TaskRunner.State.RUNNING) {
            addLog("Active GUI scan complete", LogLevel.INFO);
            lobbyStep = LobbyStep.DONE;
            guiScanCompleted = true;
        }
    }

    private boolean detectAuthChatMessages() {
        DetectionPipeline pipeline = pipelineSupplier.get();
        if (pipeline == null) return false;
        for (String msg : pipeline.getChatMessageBuffer()) {
            String lower = msg.toLowerCase(Locale.ROOT);
            for (String keyword : AUTH_CAPTCHA_KEYWORDS) {
                if (lower.contains(keyword)) return true;
            }
        }
        return false;
    }

    private void advanceLobbyStep(Minecraft mc) {
        switch (lobbyStep) {
            case HOTBAR_SCAN -> {
                if (mc.screen instanceof AbstractContainerScreen<?>) {
                    addLog("GUI opened, scanning for game-mode items", LogLevel.INFO);
                    activeContainerWalk = new ContainerWalkTask();
                    engine.clearTasks();
                    engine.addTask(activeContainerWalk);
                    engine.start();
                    lobbyStep = LobbyStep.GUI_SCAN;
                } else if (activeSlotScan != null && !activeSlotScan.hasFoundItems()) {
                    if (detectAuthChatMessages()) {
                        addLog("Auth/captcha server detected, skipping", LogLevel.WARN);
                        proceedToDisconnectThen(() -> handleServerFailureNoRetry("auth/captcha required"));
                        return;
                    }
                    if (config.npcFallbackEnabled) {
                        addLog("No selector items in hotbar, trying NPC iteration", LogLevel.INFO);
                        startNpcIteration();
                    } else {
                        addLog("No selector items in hotbar, NPC fallback disabled", LogLevel.INFO);
                        lobbyStep = LobbyStep.DONE;
                    }
                } else {
                    if (config.npcFallbackEnabled) {
                        addLog("Hotbar items found but no GUI opened, trying NPC iteration", LogLevel.WARN);
                        startNpcIteration();
                    } else {
                        addLog("Hotbar items found but no GUI opened", LogLevel.WARN);
                        lobbyStep = LobbyStep.DONE;
                    }
                }
            }
            case GUI_SCAN -> {
                if (activeContainerWalk != null && activeContainerWalk.hasFailed()
                        && activeSlotScan != null && activeSlotScan.hasMoreCandidates()) {
                    addLog("No game-mode match in GUI, trying next item", LogLevel.INFO);
                    if (activeSlotScan.resumeFromFailedGui()) {
                        engine.clearTasks();
                        engine.addTask(activeSlotScan);
                        engine.start();
                        lobbyStep = LobbyStep.HOTBAR_SCAN;
                    } else if (config.npcFallbackEnabled) {
                        addLog("All hotbar items exhausted, trying NPC iteration", LogLevel.INFO);
                        startNpcIteration();
                    } else {
                        lobbyStep = LobbyStep.DONE;
                    }
                } else if (activeContainerWalk != null && activeContainerWalk.hasFailed()) {
                    if (config.npcFallbackEnabled) {
                        addLog("No game-mode match in GUI, trying NPC iteration", LogLevel.INFO);
                        startNpcIteration();
                    } else {
                        lobbyStep = LobbyStep.DONE;
                    }
                } else {
                    addLog("GUI scan complete, waiting for transfer", LogLevel.INFO);
                    lobbyDoneAtTick = ticksInPhase;
                    lobbyStep = LobbyStep.DONE;
                }
            }
            case NPC_ITERATION -> {
                if (activeNpcTask == null) {
                    lobbyStep = LobbyStep.DONE;
                    return;
                }

                if (npcSubStep == NpcSubStep.INSPECT) {
                    EntityInspectTask.Result npcResult = activeNpcTask.getResult();

                    if (npcResult == EntityInspectTask.Result.GUI_OPENED) {
                        addLog("NPC opened GUI, scanning for game-mode items", LogLevel.INFO);
                        npcContainerWalk = new ContainerWalkTask();
                        engine.clearTasks();
                        engine.addTask(npcContainerWalk);
                        engine.start();
                        npcSubStep = NpcSubStep.GUI_SCAN;
                    } else if (npcResult == EntityInspectTask.Result.ALL_EXHAUSTED) {
                        if (detectAuthChatMessages()) {
                            addLog("Auth/captcha server detected (NPCs exhausted), skipping", LogLevel.WARN);
                            proceedToDisconnectThen(() -> handleServerFailureNoRetry("auth/captcha required"));
                            return;
                        }
                        addLog("All NPCs exhausted (" + MAX_NPC_ATTEMPTS + " tried), marking failed", LogLevel.WARN);
                        lobbyStep = LobbyStep.DONE;
                    } else {
                        addLog("NPC interaction complete (possible transfer), waiting", LogLevel.INFO);
                        lobbyDoneAtTick = ticksInPhase;
                        lobbyStep = LobbyStep.DONE;
                    }
                } else if (npcSubStep == NpcSubStep.GUI_SCAN) {
                    if (npcContainerWalk != null && npcContainerWalk.hasFailed()) {
                        addLog("No game-mode match in NPC GUI, trying next NPC", LogLevel.INFO);
                        if (activeNpcTask.resumeAfterGuiFail()) {
                            engine.clearTasks();
                            engine.addTask(activeNpcTask);
                            engine.start();
                            npcSubStep = NpcSubStep.INSPECT;
                        } else {
                            addLog("All NPCs exhausted, marking failed", LogLevel.WARN);
                            lobbyStep = LobbyStep.DONE;
                        }
                    } else {
                        addLog("NPC GUI scan matched, waiting for transfer", LogLevel.INFO);
                        lobbyDoneAtTick = ticksInPhase;
                        lobbyStep = LobbyStep.DONE;
                    }
                }
            }
            default -> lobbyStep = LobbyStep.DONE;
        }
    }

    private void startNpcIteration() {
        npcAttemptCount = 0;
        npcSubStep = NpcSubStep.INSPECT;
        activeNpcTask = new EntityInspectTask(false, MAX_NPC_ATTEMPTS);
        npcContainerWalk = null;
        engine.clearTasks();
        engine.addTask(activeNpcTask);
        engine.start();
        lobbyStep = LobbyStep.NPC_ITERATION;
    }

    private void finishScan(DetectionPipeline pipeline) {
        double confidence = pipeline.getSessionConfidence().getConfidence();
        ServerSession session = sessionSupplier.get();

        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null && session != null) {
            session.setPlayerCount(mc.getConnection().getOnlinePlayers().size());
        }
        int pluginCount = session != null ? session.getPlugins().size() : 0;

        if (confidence < config.lobbyThreshold) {
            addLog("Still in lobby (confidence " + String.format("%.0f%%", confidence * 100)
                    + "), marking failed", LogLevel.WARN);
            proceedToDisconnectThen(() -> handleServerFailure("stuck in lobby"));
            return;
        } else {
            addLog("Scan complete: " + pluginCount + " plugins detected", LogLevel.SUCCESS);
            if (session != null) {
                JsonLogger.writeLog(session.toLogData());
            }
            markServerCompleted();
        }
        proceedToDisconnectOrDelay();
    }

    private void tickDisconnecting(Minecraft mc) {
        if (disconnectEventReceived) {
            disconnectEventReceived = false;
            addLog("Disconnected from " + state.currentServer, LogLevel.INFO);
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

        if (ticksInPhase >= 20) {
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

    private void saveAndDisableSettings() {
        ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
        savedPassiveGuiDetection = mainConfig.passiveGuiDetection;
        mainConfig.passiveGuiDetection = false;
    }

    private void restoreSettings() {
        ArchivistConfig mainConfig = ArchivistMod.getInstance().getConfig();
        mainConfig.passiveGuiDetection = savedPassiveGuiDetection;
    }

    private void handleServerFailure(String reason) {
        consecutiveFailures++;

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

    private java.util.function.Predicate<String> buildSkipPredicate() {
        if (!config.skipAlreadyLogged) return null;
        Path logsDir = JsonLogger.getLogsDirectory();
        return server -> {
            String safe = server.replace(":", "_");
            if (Files.exists(logsDir.resolve(safe + ".json"))) return true;
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
            state.stoppedAtEpochMs = System.currentTimeMillis();
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
                if (mc.screen instanceof DisconnectedScreen) {
                    mc.disconnect(new TitleScreen(), false);
                }
                ServerData serverData = new ServerData(server, server, ServerData.Type.OTHER);
                ServerAddress parsed = ServerAddress.parseString(server);
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
                mc.disconnect(new TitleScreen(), false);
            } catch (Exception e) {
                LOGGER.warn("Error during disconnect", e);
            }
            disconnectEventReceived = true;
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
        addLog(message, LogLevel.INFO);
    }

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
