package com.archivist;

import com.archivist.account.AccountManager;
import com.archivist.api.ApiSyncManager;
import com.archivist.bridge.MixinBridge;
import com.archivist.command.CommandRegistry;
import com.archivist.command.commands.*;
import com.archivist.config.ArchivistConfig;
import com.archivist.config.ConfigManager;
import com.archivist.data.EventBus;
import com.archivist.data.JsonLogger;
import com.archivist.data.LogEvent;
import com.archivist.data.ServerLogData;
import com.archivist.data.ServerSession;
import com.archivist.detection.Detection;
import com.archivist.detection.DetectionPipeline;
import com.archivist.detection.DetectionType;
import com.archivist.detection.SessionConfidence;
import com.archivist.detection.PluginGlossary;
import com.archivist.detection.fingerprint.*;
import com.archivist.gui.overlay.ScanProgressOverlay;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.ThemeManager;
import com.archivist.gui.screen.ArchivistScreen;
import com.archivist.proxy.ProxyManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
//? if >=1.21.11
import net.minecraft.resources.Identifier;
//? if <1.21.11
/*import net.minecraft.resources.ResourceLocation;*/
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ArchivistMod implements ClientModInitializer {

    public static final String MOD_ID = "archivist";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ArchivistMod instance;

    private ArchivistConfig config;
    private EventBus eventBus;
    private ServerSession session;
    private PluginGlossary glossary;
    private DetectionPipeline pipeline;
    private static ArchivistScreen cachedScreen;

    public static void setCachedScreen(ArchivistScreen screen) { cachedScreen = screen; }
    private ApiSyncManager apiSyncManager;
    private com.archivist.api.automation.TaskRunner automationEngine;
    private ScanProgressOverlay scanOverlay;

    private CommandRegistry commandRegistry;
    private GuiRuleDatabase guiRuleDatabase;
    private GuiRuleMatcher guiRuleMatcher;
    private PluginHeuristics pluginHeuristics;
    private AccountManager accountManager;
    private ProxyManager proxyManager;

    private com.archivist.api.automation.SequenceHandler automationOrchestrator;
    private com.archivist.api.automation.ScheduleConfig automationConfig;
    private com.archivist.api.automation.SequenceState automationState;

    private RuleCaptureManager ruleCaptureManager;

    private KeyMapping openGuiKey;
    private KeyMapping captureGuiKey;
    private boolean guiKeyWasDown;
    private boolean captureKeyWasDown;

    public static ArchivistMod getInstance() { return instance; }
    public ArchivistConfig getConfig() { return config; }
    public EventBus getEventBus() { return eventBus; }
    public ServerSession getSession() { return session; }
    public DetectionPipeline getPipeline() { return pipeline; }
    public ApiSyncManager getApiSyncManager() { return apiSyncManager; }
    public com.archivist.api.automation.TaskRunner getTaskRunner() { return automationEngine; }
    public CommandRegistry getCommandRegistry() { return commandRegistry; }
    public GuiRuleDatabase getGuiRuleDatabase() { return guiRuleDatabase; }
    public GuiRuleMatcher getGuiRuleMatcher() { return guiRuleMatcher; }
    public RuleCaptureManager getRuleCaptureManager() { return ruleCaptureManager; }
    public AccountManager getAccountManager() { return accountManager; }
    public ProxyManager getProxyManager() { return proxyManager; }
    public PluginGlossary getGlossary() { return glossary; }
    public com.archivist.api.automation.SequenceHandler getSequenceHandler() { return automationOrchestrator; }
    public com.archivist.api.automation.ScheduleConfig getScheduleConfig() { return automationConfig; }

    @Override
    public void onInitializeClient() {
        instance = this;

        // Ensure archivist directories exist
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("archivist/libs"));
        } catch (Exception ignored) {}

        config = ConfigManager.load();
        eventBus = new EventBus();
        session = new ServerSession(eventBus);

        glossary = new PluginGlossary();
        session.setGlossary(glossary);
        glossary.load();

        pipeline = new DetectionPipeline(glossary);
        apiSyncManager = new ApiSyncManager(() -> config, eventBus);
        automationEngine = new com.archivist.api.automation.TaskRunner(
                eventBus, () -> pipeline.getSessionConfidence());
        automationConfig = com.archivist.api.automation.ScheduleConfig.load();
        automationEngine.setLobbyThreshold(automationConfig.lobbyThreshold);
        automationState = com.archivist.api.automation.SequenceState.load();
        com.archivist.api.automation.AddressListManager.ensureDirectory();
        automationOrchestrator = new com.archivist.api.automation.SequenceHandler(
                automationEngine, eventBus, () -> pipeline, () -> session,
                automationConfig, automationState);
        scanOverlay = new ScanProgressOverlay(config, pipeline);
        scanOverlay.setOrchestrator(automationOrchestrator);
        scanOverlay.setDomainSupplier(() -> session != null ? session.getDomain() : "unknown");

        pluginHeuristics = new PluginHeuristics();
        pluginHeuristics.load();

        // GUI rule-based detection
        guiRuleDatabase = new GuiRuleDatabase(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
        guiRuleDatabase.load();
        guiRuleMatcher = new GuiRuleMatcher();
        ruleCaptureManager = new RuleCaptureManager();

        // Account & proxy
        accountManager = new AccountManager();
        accountManager.load();
        accountManager.captureOriginalAccount();
        proxyManager = new ProxyManager();
        proxyManager.load();

        // Command registry
        initCommandRegistry();

        // Themes
        ThemeManager.getInstance().load();
        ColorScheme theme = ThemeManager.getInstance().getTheme(config.activeTheme);
        if (theme != null) ColorScheme.setActive(theme);

        // Wire mixin bridge
        MixinBridge.activate(pipeline, session);
        MixinBridge.setPassiveGuiCallback(capture -> {
            ruleCaptureManager.setLastCapture(capture);
            if (config.passiveGuiDetection && session != null) {
                double sessionConf = pipeline.getSessionConfidence().getConfidence();
                processCapture(capture, sessionConf);
            }
        });

        //? if >=1.21.9 {
        KeyMapping.Category ARCHIVIST_CATEGORY = KeyMapping.Category.register(
                //? if >=1.21.11
                Identifier.fromNamespaceAndPath("archivist", "archivist")
                //? if <1.21.11
                /*ResourceLocation.fromNamespaceAndPath("archivist", "archivist")*/
        );
        openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.archivist.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, ARCHIVIST_CATEGORY));
        captureGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.archivist.capture", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, ARCHIVIST_CATEGORY));
        //?} else {
        /*openGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.archivist.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.archivist"));
        captureGuiKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.archivist.capture", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.archivist"));*/
        //?}

        HudRenderCallback.EVENT.register((g, tickCounter) -> scanOverlay.render(g));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            eventBus.reset();
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Archivist active"));
            session.reset();
            pipeline.onServerJoin(session);
            apiSyncManager.onServerJoin();

            MixinBridge.activate(pipeline, session);
            MixinBridge.setPassiveGuiCallback(capture -> {
                ruleCaptureManager.setLastCapture(capture);
                if (config.passiveGuiDetection && session != null) {
                    double sessionConf = pipeline.getSessionConfidence().getConfidence();
                    processCapture(capture, sessionConf);
                }
            });

            captureConnectionMetadata(handler, client);
            pipeline.onDomainResolved(session.getDomain());
            eventBus.post(new LogEvent(LogEvent.Type.CONNECT,
                    "Connected to " + session.getDomain() + " (" + session.getIp() + ":" + session.getPort() + ")"));
            scanOverlay.startScan(60);
            automationOrchestrator.onServerJoined();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            String serverAddr = session != null ? session.getDomain() : null;
            String serverIp = session != null ? session.getIp() : null;
            boolean excluded = config.isExcluded(serverAddr) || config.isExcluded(serverIp);

            if (!excluded) {
                apiSyncManager.onDisconnect(session.toLogData(), pipeline.getSessionConfidence().getConfidence());
                JsonLogger.writeLog(session.toLogData());
            }
            eventBus.post(new LogEvent(LogEvent.Type.DISCONNECT, "Disconnected"));
            pipeline.reset();
            scanOverlay.reset();
            MixinBridge.deactivate();
            automationOrchestrator.onServerDisconnected();
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // These must tick even when disconnected so automation can dismiss kick screens
            automationEngine.tick();
            automationOrchestrator.tick();

            if (client.level != null && client.getConnection() != null) {
                pipeline.tick();
                scanOverlay.tick();
                apiSyncManager.tick();

                // Feed client-side signals into lobby detection
                SessionConfidence sc = pipeline.getSessionConfidence();
                if (client.player != null) {
                    try {
                        sc.onInventoryTick(client.player);
                    } catch (Exception e) {
                        LOGGER.debug("onInventoryTick failed", e);
                    }
                    try {
                        sc.onWorldProperties(client);
                    } catch (Exception e) {
                        LOGGER.debug("onWorldProperties failed", e);
                    }
                }
                // Scoreboard sidebar analysis
                if (client.level != null) {
                    try {
                        var scoreboard = client.level.getScoreboard();
                        var objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
                        if (objective != null) {
                            var lines = new java.util.ArrayList<String>();
                            for (var entry : scoreboard.listPlayerScores(objective)) {
                                var team = scoreboard.getPlayersTeam(entry.owner());
                                String line = team != null
                                        ? team.getPlayerPrefix().getString() + entry.owner() + team.getPlayerSuffix().getString()
                                        : entry.owner();
                                lines.add(line);
                            }
                            lines.add(objective.getDisplayName().getString());
                            sc.onScoreboardSidebar(lines);
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Scoreboard sidebar analysis failed", e);
                    }
                }
            }

            // Cancel capture if screen closed while selecting
            if (ruleCaptureManager.getState() == RuleCaptureManager.State.SELECTING
                    && client.screen == null) {
                ruleCaptureManager.cancel();
            }

            // Capture GUI rule keybind (direct GLFW polling so it works while a GUI is open)
            boolean captureKeyDown = GLFW.glfwGetKey(GLFW.glfwGetCurrentContext(),
                    KeyBindingHelper.getBoundKeyOf(captureGuiKey).getValue()) == GLFW.GLFW_PRESS;
            if (captureKeyDown && !captureKeyWasDown) {
                var capState = ruleCaptureManager.getState();
                if (capState == RuleCaptureManager.State.INACTIVE) {
                    if (client.screen != null && !(client.screen instanceof ArchivistScreen)) {
                        ruleCaptureManager.startCapture();
                    }
                } else if (capState == RuleCaptureManager.State.SELECTING) {
                    ruleCaptureManager.cancel();
                    eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Capture cancelled"));
                }
            }
            captureKeyWasDown = captureKeyDown;

            if (openGuiKey.consumeClick()) {
                if (cachedScreen == null) {
                    cachedScreen = new ArchivistScreen(session, eventBus, config, pipeline);
                }
                cachedScreen.setParentScreen(null);
                client.setScreen(cachedScreen);
                guiKeyWasDown = true; // prevent close branch from firing on same tick
            }

            if (client.screen != null && !(client.screen instanceof ArchivistScreen)) {
                boolean keyDown = GLFW.glfwGetKey(GLFW.glfwGetCurrentContext(),
                        KeyBindingHelper.getBoundKeyOf(openGuiKey).getValue()) == GLFW.GLFW_PRESS;
                if (keyDown && !guiKeyWasDown) {
                    if (cachedScreen == null) {
                        cachedScreen = new ArchivistScreen(client.screen, session, eventBus, config, pipeline);
                    } else {
                        cachedScreen.setParentScreen(client.screen);
                    }
                    client.setScreen(cachedScreen);
                }
                guiKeyWasDown = keyDown;
            } else if (client.screen instanceof ArchivistScreen archScreen) {
                boolean keyDown = GLFW.glfwGetKey(GLFW.glfwGetCurrentContext(),
                        KeyBindingHelper.getBoundKeyOf(openGuiKey).getValue()) == GLFW.GLFW_PRESS;
                if (keyDown && !guiKeyWasDown) {
                    archScreen.onClose();
                }
                guiKeyWasDown = keyDown;
            } else {
                guiKeyWasDown = false;
            }
        });

        LOGGER.info("Archivist initialized");
    }

    private void initCommandRegistry() {
        commandRegistry = new CommandRegistry();
        commandRegistry.register(new HelpCommand(() -> commandRegistry));
        commandRegistry.register(new ClearCommand(() -> eventBus.reset()));
        commandRegistry.register(new InfoCommand(() -> session));
        commandRegistry.register(new PluginsCommand(() -> session));
        commandRegistry.register(new ScanCommand(() -> pipeline, () -> session));
        commandRegistry.register(new ExportCommand(() -> session, () -> eventBus));
        commandRegistry.register(new DbCommand(() -> apiSyncManager));
        commandRegistry.register(new ThemeCommand(() -> config));
        commandRegistry.register(new InspectorCommand(() -> {}));
        commandRegistry.register(new NpcCommand(() -> automationEngine, () -> pipeline.getSessionConfidence()));
    }

    /**
     * Process a single GUI capture against all detection systems.
     */
    void processCapture(GuiCapture capture, double sessionConf) {
        if (guiRuleMatcher == null || pluginHeuristics == null) {
            LOGGER.warn("processCapture called before detection systems initialized");
            return;
        }
        // GUI rule matching (new system)
        var ruleMatches = guiRuleMatcher.match(capture, guiRuleDatabase.getAll());
        for (var match : ruleMatches) {
            double effective = sessionConf * match.confidence();
            session.addGuiPlugin(new ServerLogData.GuiPluginEntry(
                    match.rule().pluginId(), match.rule().pluginName(),
                    effective, capture.title(), match.matchedMarkers()));
            session.addDetection(Detection.of(match.rule().pluginName(),
                    DetectionType.FINGERPRINT, effective,
                    "gui-rule: " + match.rule().pluginId() + "/" + match.pattern().label()));
        }

        // Heuristic matching
        List<String> heuristicPlugins = pluginHeuristics.match(capture.title(), capture.items());
        for (String plugin : heuristicPlugins) {
            double effective = sessionConf * DetectionType.HEURISTIC.defaultConfidence();
            session.addDetection(Detection.of(plugin, DetectionType.HEURISTIC, effective,
                    "heuristic: " + capture.title()));
        }
    }

    private void captureConnectionMetadata(net.minecraft.client.multiplayer.ClientPacketListener handler, Minecraft client) {
        try {
            var connection = handler.getConnection();
            var remoteAddr = connection.getRemoteAddress();
            String resolvedIp = null;
            if (remoteAddr instanceof java.net.InetSocketAddress inet) {
                resolvedIp = inet.getAddress().getHostAddress();
                session.setIp(resolvedIp);
                session.setPort(inet.getPort());
            }
            var serverData = client.getCurrentServer();
            if (serverData != null) {
                String typedAddr = serverData.ip;
                String domain = null;
                if (!typedAddr.isEmpty()) {
                    int lastColon = typedAddr.lastIndexOf(':');
                    if (lastColon > 0) {
                        domain = typedAddr.substring(0, lastColon);
                        try { session.setPort(Integer.parseInt(typedAddr.substring(lastColon + 1))); }
                        catch (NumberFormatException ignored) { session.setPort(25565); }
                    } else {
                        domain = typedAddr;
                        session.setPort(25565);
                    }
                }

                boolean exception = config.isException(domain);
                if (exception) {
                    session.setIp("unknown");
                    session.setDomain("unknown");
                    pipeline.setChatUrlExtractionEnabled(false);
                } else {
                    if (domain != null) {
                        session.setDomain(domain);
                        try {
                            String dnsIp = java.net.InetAddress.getByName(domain).getHostAddress();
                            if (dnsIp != null) {
                                session.setIp(dnsIp);
                            }
                        } catch (Exception ignored) {}
                    }
                }

                if (serverData.players != null) {
                    int count = serverData.players.online();
                    int max = serverData.players.max();
                    session.setPlayerCount(count);
                    session.setMaxPlayers(max);
                    eventBus.post(new LogEvent(LogEvent.Type.CONNECT, "Players: " + count + "/" + max));
                    int tabListCount = handler.getOnlinePlayers().size();
                    pipeline.onPlayerCount(count, tabListCount);
                }
                if (serverData.version != null) {
                    String ver = serverData.version.getString();
                    if (ver.length() > 20) ver = ver.substring(0, 20);
                    session.setVersion(ver);
                }
            }
            if (!"unknown".equals(session.getVersion())) {
                eventBus.post(new LogEvent(LogEvent.Type.CONNECT, "Version: " + session.getVersion()));
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to capture connection metadata", e);
        }
    }

}
