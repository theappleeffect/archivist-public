package com.archivist.gui.screen;

import com.archivist.ArchivistMod;
import com.archivist.command.CommandRegistry;
import com.archivist.config.ArchivistConfig;
import com.archivist.config.ConfigManager;
import com.archivist.data.EventBus;
import com.archivist.data.JsonLogger;
import com.archivist.data.LogEvent;
import com.archivist.data.LogExporter;
import com.archivist.data.ServerLogData;
import com.archivist.data.ServerSession;
import com.archivist.detection.Detection;
import com.archivist.detection.DetectionPipeline;
import com.archivist.detection.SessionConfidence;
import com.archivist.detection.fingerprint.CapturedItem;
import com.archivist.detection.fingerprint.GuiCapture;
import com.archivist.gui.WindowStateManager;
import com.archivist.gui.overlay.ArchivistWebPromptOverlay;
import com.archivist.gui.overlay.TutorialOverlay;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.GradientConfig;
import com.archivist.gui.render.RainbowTheme;
import com.archivist.gui.render.RenderUtils;
import com.archivist.gui.render.ThemeGenerator;
import com.archivist.gui.render.ThemeManager;
import com.archivist.gui.panel.PanelModeLayout;
import com.archivist.gui.panel.SplitPane;
import com.archivist.gui.widgets.*;
import com.archivist.util.ArchivistExecutor;
import com.archivist.util.IpInfoLookup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Main Archivist GUI screen. Extends Minecraft's Screen as a bridge —
 * all rendering and input is forwarded to the custom widget system.
 * No vanilla widgets (addDrawableChild) are used.
 *
 * Creates and manages: Server Info, Plugin List,
 * Connection Log, Console, Settings, Inspector, Server List,
 * Manual Log, Account Manager, and Proxy Manager windows plus the Taskbar.
 */
public class ArchivistScreen extends Screen {

    private final List<DraggableWindow> windows = new ArrayList<>();
    private final List<DraggableWindow> pendingRemoval = new ArrayList<>();
    private final List<DraggableWindow> taskbarOrder = new ArrayList<>();
    private final Taskbar taskbar = new Taskbar();

    // Global search overlay
    private final GlobalSearchOverlay globalSearch = new GlobalSearchOverlay();

    // Keyboard shortcut state
    private boolean shortcutConsumedThisFrame = false;

    // Deferred rebuild flag (for theme changes that can't call init() mid-callback)
    private boolean needsRebuild = false;

    // Screen-level open/close animation (macOS-style pop-in/pop-out)
    private enum ScreenAnimState { NONE, OPENING, CLOSING }
    private ScreenAnimState screenAnimState = ScreenAnimState.NONE;
    private float screenAnimProgress = 1f;
    private static final float SCREEN_ANIM_SPEED = 0.08f;
    private static final float SCREEN_SCALE_START = 0.85f;
    private boolean hasOpenedBefore = false;

    // Report mode
    private boolean reportMode = false;
    private float reportScroll = 0;
    private float reportMaxScroll = 0;
    private static final int REPORT_MARGIN = 20;
    private static final int REPORT_LINE_H = 10;

    // Mouse position (updated each render frame for key handlers)
    private int lastMouseX, lastMouseY;

    // True when viewing a saved server log (prevents live data from overwriting)
    private boolean viewingServerLog = false;

    // Tab persistence across rebuilds
    private static int settingsActiveTab = 0;

    // Layout section widget refs for tutorial highlight
    private static Widget layoutSectionFirst = null;
    private static Widget layoutSectionLast = null;
    private static int automationActiveTab = 0;

    // Theme creator panel (persists across rebuilds for customModified state)
    private ThemeCreatorPanel themeCreatorPanel;

    // Tutorial overlay (persists across rebuilds)
    private TutorialOverlay tutorialOverlay;

    // Archivist-web prompt overlay (shown after tutorial)
    private ArchivistWebPromptOverlay archivistWebPrompt;

    // Persistent text field contents (survives init() rebuilds)
    private static final Map<String, String> savedTextFields = new HashMap<>();
    private static String savedFocusedField = null;
    private static int savedCursorPos = -1;

    // Tracks detached section names across init() rebuilds (cleared on screen close)
    private static final Set<String> detachedSectionNames = new HashSet<>();

    // Persists active section in dynamic mode across init() rebuilds
    private static String savedActiveSection = null;

    // Account manager add-section type persistence
    private static String accountAddType = "Offline";

    // Tracks how many server IP fields in the new list creator
    private static int newListServerCount = 1;

    // Real-time save throttle
    private int saveThrottleCounter = 0;

    // Cursor cache (lazily created, shared across instances)
    private static boolean cursorsInitialized = false;
    private static long cursorArrow, cursorHand, cursorHResize, cursorVResize;
    private static long cursorNWSE, cursorNESW, cursorMove;
    private static DraggableWindow.CursorType lastCursorType = DraggableWindow.CursorType.ARROW;

    // Windows
    private DraggableWindow serverInfoWindow;
    private DraggableWindow consoleWindow;
    private DraggableWindow settingsWindow;
    private DraggableWindow inspectorWindow;
    private DraggableWindow serverListWindow;
    private DraggableWindow manualLogWindow;
    private DraggableWindow accountManagerWindow;
    private DraggableWindow proxyManagerWindow;
    private DraggableWindow automationWindow;

    // Layout mode: "windows" or "dynamic"
    private String layoutMode;
    private boolean panelMode; // true for dynamic mode (single DraggableWindow with sidebar + content + detach)
    private PanelModeLayout panelLayout;

    // Automation GUI state
    private Label automationStatusLabel;
    private Label automationTaskLabel;
    private Label automationCurrentLabel;
    private Label automationProgressLabel;
    private Label automationFailedLabel;
    private Label automationElapsedLabel;
    private ScrollableList automationLogList;
    private int automationLogLastCount = 0;

    // Console state
    private ScrollableList consoleOutput;
    private TextField consoleInput;
    private int lastEventCount = 0;
    private int lastKnownPluginCount = 0;
    private long lastServerInfoRebuild = 0;

    // Inspector state
    private ScrollableList inspectorList;

    // Refresh runnables for keyboard shortcut support (Ctrl+R)
    private Runnable refreshPlugins;
    private Runnable refreshLogs;
    private Runnable refreshAccounts;
    private Runnable refreshInspector;

    // Delete runnable for keyboard shortcut support (Delete key)
    private Runnable deleteSelectedRule;
    private Runnable deleteSelectedAccount;

    // Rules list reference for delete shortcut
    private ScrollableList rulesListRef;

    // Account list reference for delete shortcut
    private ScrollableList accountListRef;

    private Screen parent;
    private boolean closedIntentionally = false;

    // External dependencies
    private final ServerSession session;
    private final EventBus eventBus;
    private final ArchivistConfig config;
    private final DetectionPipeline pipeline;
    private final IpInfoLookup ipInfoLookup = new IpInfoLookup();
    private volatile IpInfoLookup.IpInfoResult ipInfoResult;

    // Window state persistence
    private static Map<String, WindowStateManager.WindowState> savedWindowStates;

    public ArchivistScreen(ServerSession session, EventBus eventBus, ArchivistConfig config, DetectionPipeline pipeline) {
        this(null, session, eventBus, config, pipeline);
    }

    public ArchivistScreen(Screen parent, ServerSession session, EventBus eventBus, ArchivistConfig config, DetectionPipeline pipeline) {
        super(Component.literal("Archivist"));
        this.parent = parent;
        this.session = session;
        this.eventBus = eventBus;
        this.config = config;
        this.pipeline = pipeline;
        triggerIpInfoLookup();
    }

    private void triggerIpInfoLookup() {
        String address = session.getDomain();
        if (address == null || address.equals("unknown") || address.isBlank()) return;
        ArchivistExecutor.execute(() -> {
            ipInfoResult = ipInfoLookup.lookup(address);
        });
    }

    public void setParentScreen(Screen parent) { this.parent = parent; }

    // Tracks whether the screen has been fully initialized (skip rebuild on reshow)
    private boolean initialized = false;

    @Override
    protected void init() {
        // If already initialized and not a forced rebuild, just reflow for resize
        if (initialized && !needsRebuild) {
            if (parent != null) {
                parent.width = width;
                parent.height = height;
            }
            for (DraggableWindow w : windows) {
                w.reflow(0, 0, width, height);
            }
            if (!panelMode && taskbar != null) {
                taskbar.updatePosition(width, height);
            }
            // Reset taskbar visibility on reshow (slide-out may have hidden it)
            if (!panelMode) {
                taskbar.startSlideIn();
            }
            // Reset screen animation state for reshow (only if no animation is playing)
            if (screenAnimState == ScreenAnimState.NONE) {
                screenAnimProgress = 1f;
            }
            closedIntentionally = false;
            return;
        }

        // Propagate resize to parent so it renders correctly underneath
        if (parent != null) {
            parent.width = width;
            parent.height = height;
        }

        // Save active section in dynamic mode before rebuild
        if (panelLayout != null) {
            savedActiveSection = panelLayout.getActiveSection();
        }

        // Save current positions before clearing (for theme changes / rebuilds)
        if (!windows.isEmpty()) {
            Map<String, WindowStateManager.WindowState> current = new HashMap<>();
            for (DraggableWindow w : windows) {
                current.put(w.getId(), new WindowStateManager.WindowState(
                        w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                        w.isVisible(), w.isMinimized()
                ));
            }
            savedWindowStates = current;
        }

        // Save active tab states before clearing windows
        saveActiveTab(settingsWindow, v -> settingsActiveTab = v);
        saveActiveTab(automationWindow, v -> automationActiveTab = v);
        if (panelLayout != null) {
            // In dynamic mode, tabs are inside the content panel
            var cp = panelLayout.getContentPanel();
            if (cp != null) {
                saveActiveTab(cp, v -> settingsActiveTab = v);
                saveActiveTab(cp, v -> automationActiveTab = v);
            }
        }

        windows.clear();

        // Load saved window states from disk on first init
        if (savedWindowStates == null) {
            savedWindowStates = WindowStateManager.load();
        }

        // Apply saved theme
        applyTheme(config.activeTheme);
        ColorScheme.setGradientsEnabled(config.gradientsEnabled);
        ColorScheme.setBackgroundGradientEnabled(config.backgroundGradientEnabled);

        // Sync animation setting — suppress animations during setup
        DraggableWindow.animationsEnabled = config.guiAnimations;
        DraggableWindow.resetAnimReady();

        // Read layout mode from config
        layoutMode = config.layoutMode != null ? config.layoutMode : "windows";
        panelMode = "dynamic".equals(layoutMode) || "panel".equals(layoutMode);

        if (panelMode) {
            // ── Dynamic Mode Setup (single DraggableWindow with sidebar + content + detach) ──
            panelLayout = new PanelModeLayout(0, 0, 100, 100); // size doesn't matter, FILL anchor handles it

            // Enable detach BEFORE adding sections so buttons get drag callbacks
            panelLayout.setDetachEnabled(true);

            // Top group sections
            panelLayout.addSection("Server & Plugins", panel -> buildServerInfoPanel(panel), false);
            panelLayout.addSection("Server List", panel -> buildServerListPanel(panel), false);
            panelLayout.addSection("Console", panel -> buildConsolePanel(panel), false);
            panelLayout.addSection("Automation", panel -> buildAutomationPanel(panel), false);
            panelLayout.addSection("Inspector", panel -> buildInspectorPanel(panel), false);
            panelLayout.addSection("Manual Log", panel -> buildManualLogPanel(panel), false);

            // Bottom group sections
            panelLayout.addSection("Settings", panel -> buildSettingsPanel(panel), true);
            panelLayout.addSection("Accounts", panel -> buildAccountManagerPanel(panel), true);
            panelLayout.addSection("Proxy", panel -> buildProxyManagerPanel(panel), true);

            // Apply saved sidebar order
            if (config.sidebarOrder != null && !config.sidebarOrder.isEmpty()) {
                panelLayout.applySidebarOrder(config.sidebarOrder);
            }

            // Restore detached section state from previous rebuild
            for (String detached : detachedSectionNames) {
                panelLayout.markDetached(detached);
            }

            // Sidebar position and detach callbacks
            panelLayout.setSidebarSide(config.sidebarPosition != null ? config.sidebarPosition : "left");

            panelLayout.setOnDetachRequest(event -> {
                // Create a floating window at the mouse position
                double evtX = event.mouseX();
                double evtY = event.mouseY();

                String sectionName = event.sectionName();
                String windowId = "dynamic_" + sectionName.toLowerCase();
                DraggableWindow floatingWindow = new DraggableWindow(windowId, sectionName,
                        (int) evtX - 150, (int) evtY - 10, 300, 250);

                // Build section content into the window
                Panel contentPanel = new Panel(0, 0, 280, 230);
                contentPanel.setAnchor(Widget.Anchor.FILL);
                contentPanel.setPadding(4);
                contentPanel.setSpacing(2);

                SectionEntry sectionEntry = findSection(sectionName);
                if (sectionEntry != null) {
                    sectionEntry.contentBuilder().accept(contentPanel);
                }
                floatingWindow.addChild(contentPanel);

                // On close: animate back to sidebar button then re-dock
                floatingWindow.setOnClose(() -> {
                    String section = floatingWindow.getTitle();
                    // Update origin to current sidebar button position for close animation
                    int[] btnCenter = panelLayout.getSidebarButtonCenter(section);
                    if (btnCenter != null) {
                        floatingWindow.setAnimOrigin(btnCenter[0], btnCenter[1]);
                    }
                    floatingWindow.forceCloseAnimation(() -> {
                        // Defer removal to next tick to avoid ConcurrentModificationException
                        pendingRemoval.add(floatingWindow);
                    });
                });

                // Animate from sidebar button position
                floatingWindow.setAnimOrigin((int) evtX, (int) evtY);
                floatingWindow.forceOpenAnimation();
                floatingWindow.setAllWindows(windows);
                windows.add(floatingWindow);
                panelLayout.markDetached(sectionName);
                detachedSectionNames.add(sectionName);

                // Immediately start dragging so it's attached to cursor
                floatingWindow.startDragging(evtX, evtY);
            });

            // Restore active section (or default to Server & Plugins on first open)
            if ("Plugins".equals(savedActiveSection) || "Server".equals(savedActiveSection)) {
                savedActiveSection = "Server & Plugins";
            }
            panelLayout.setActiveSection(savedActiveSection != null ? savedActiveSection : "Server & Plugins");

            // Wrap in a DraggableWindow (unclosable, unminimizable)
            int screenW = width;
            int screenH = height;
            DraggableWindow panelWindow = new DraggableWindow("archivist_panel", "Archivist",
                    screenW * 15 / 100, screenH * 15 / 100, screenW * 70 / 100, screenH * 70 / 100);
            panelWindow.setCloseable(false);
            panelWindow.setMinimizable(false);
            panelLayout.setAnchor(Widget.Anchor.FILL);
            panelWindow.addChild(panelLayout);
            windows.add(panelWindow);

            // Restore detached floating windows from saved state
            if (savedWindowStates != null) {
                for (Map.Entry<String, WindowStateManager.WindowState> entry : savedWindowStates.entrySet()) {
                    if (entry.getKey().startsWith("dynamic_") && entry.getValue().visible()) {
                        String sectionName = entry.getKey().substring("dynamic_".length());
                        // Capitalize first letter
                        sectionName = sectionName.substring(0, 1).toUpperCase() + sectionName.substring(1);

                        SectionEntry se = findSection(sectionName);
                        if (se != null) {
                            WindowStateManager.WindowState state = entry.getValue();
                            DraggableWindow restoredWindow = new DraggableWindow(
                                    entry.getKey(), sectionName,
                                    state.x(), state.y(), state.width(), state.height());

                            Panel cp = new Panel(0, 0, 280, 230);
                            cp.setAnchor(Widget.Anchor.FILL);
                            cp.setPadding(4);
                            cp.setSpacing(2);
                            se.contentBuilder().accept(cp);
                            restoredWindow.addChild(cp);

                            restoredWindow.setOnClose(() -> {
                                String section = restoredWindow.getTitle();
                                windows.remove(restoredWindow);
                                panelLayout.markDocked(section);
                                detachedSectionNames.remove(section);
                            });

                            restoredWindow.setAllWindows(windows);
                            windows.add(restoredWindow);
                            panelLayout.markDetached(sectionName);
                        }
                    }
                }
            }

            // No taskbar in dynamic mode
            taskbarOrder.clear();

            // Give windows the full list for snapping
            for (DraggableWindow w : windows) {
                w.setAllWindows(windows);
            }

            // Setup global search
            globalSearch.setVisible(false);
            globalSearch.setSearchProvider(this::performGlobalSearch);

            // Start screen open animation (first open only, not rebuilds/resizes)
            if (!hasOpenedBefore && config.guiAnimations) {
                screenAnimState = ScreenAnimState.OPENING;
                screenAnimProgress = 0f;
                hasOpenedBefore = true;
                if (config.taskbarSlideAnimation) {
                    taskbar.startSlideIn();
                }
            }
        } else {
            // ── Window Mode Setup ───────────────────────────────────────────
            panelLayout = null;

            // ── Create Windows ──────────────────────────────────────────────
            serverInfoWindow = createWindow("server_info", "Server & Plugins", 10, 10, 300, 340);
            consoleWindow = createWindow("console", "Console", 10, 260, 300, 200);
            settingsWindow = createWindow("settings", "Settings", 610, 10, 220, 300);
            if (!hasSavedState("settings")) settingsWindow.setVisible(false);
            inspectorWindow = createWindow("inspector", "GUI Inspector", 610, 320, 250, 250);
            inspectorWindow.setVisible(true);
            serverListWindow = createWindow("server_list", "Server List", 10, 10, 400, 350);
            if (!hasSavedState("server_list")) serverListWindow.setVisible(false);
            manualLogWindow = createWindow("manual_log", "Manual Log", 410, 320, 220, 160);
            if (!hasSavedState("manual_log")) manualLogWindow.setVisible(false);
            accountManagerWindow = createWindow("account_manager", "Account Manager", 220, 260, 220, 180);
            if (!hasSavedState("account_manager")) accountManagerWindow.setVisible(false);
            proxyManagerWindow = createWindow("proxy_manager", "Proxy Manager", 450, 260, 220, 180);
            if (!hasSavedState("proxy_manager")) proxyManagerWindow.setVisible(false);
            automationWindow = createWindow("automation", "Automation", 450, 10, 280, 350);
            if (!hasSavedState("automation")) automationWindow.setVisible(false);

            buildServerInfoWindow();
            buildConsoleWindow();
            buildSettingsWindow();
            buildInspectorWindow();
            buildServerListWindow();
            buildManualLogWindow();
            buildAccountManagerWindow();
            buildProxyManagerWindow();
            buildAutomationWindow();

            // Initial reflow so anchored children get correct positions
            consoleWindow.reflowChildren();
            settingsWindow.reflowChildren();
            inspectorWindow.reflowChildren();
            serverListWindow.reflowChildren();
            manualLogWindow.reflowChildren();
            accountManagerWindow.reflowChildren();
            proxyManagerWindow.reflowChildren();
            automationWindow.reflowChildren();

            windows.add(serverInfoWindow);
            windows.add(serverListWindow);
            windows.add(consoleWindow);
            windows.add(settingsWindow);
            windows.add(inspectorWindow);
            windows.add(manualLogWindow);
            windows.add(accountManagerWindow);
            windows.add(proxyManagerWindow);
            windows.add(automationWindow);

            // Stable order for taskbar (never reordered by bringToFront)
            taskbarOrder.clear();
            taskbarOrder.addAll(windows);

            // Give each window the full list for snapping + taskbar reference
            for (DraggableWindow w : windows) {
                w.setAllWindows(windows);
                w.setTaskbar(taskbar);
            }

            // Taskbar
            taskbar.setPosition(parseTaskbarPosition(config.taskbarPosition));
            taskbar.setup(taskbarOrder, width);
            taskbar.updatePosition(width, height);
            // Setup global search
            globalSearch.setVisible(false);
            globalSearch.setSearchProvider(this::performGlobalSearch);
            globalSearch.setOnResultSelected((windowId, matchText) -> {
                for (DraggableWindow w : windows) {
                    if (w.getId().equals(windowId)) {
                        w.setVisible(true);
                        w.setMinimized(false);
                        bringToFront(w);
                        // Scroll to matching item in the window's ScrollableList
                        for (Widget child : w.getChildren()) {
                            if (child instanceof ScrollableList list) {
                                for (int i = 0; i < list.getItems().size(); i++) {
                                    if (list.getItems().get(i).text.equals(matchText)) {
                                        list.scrollToItem(i);
                                        list.setSelectedIndex(i);
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                        break;
                    }
                }
            });

            // Start screen open animation (first open only, not rebuilds/resizes)
            if (!hasOpenedBefore && config.guiAnimations) {
                screenAnimState = ScreenAnimState.OPENING;
                screenAnimProgress = 0f;
                hasOpenedBefore = true;
                if (config.taskbarSlideAnimation) {
                    taskbar.startSlideIn();
                }
            }

        }

        // Tutorial overlay (first open when config.tutorial is true) — runs for both layout modes
        if (config.tutorial && tutorialOverlay == null) {
            tutorialOverlay = new TutorialOverlay(config);
        }
        if (tutorialOverlay != null) {
            tutorialOverlay.setOnFinishCallback(() -> {
                if (!config.archivistWebPromptShown) {
                    archivistWebPrompt = new ArchivistWebPromptOverlay(config);
                }
            });
            tutorialOverlay.setLayoutSuppliers(
                    () -> panelLayout,
                    () -> panelMode,
                    () -> windows,
                    () -> taskbar
            );
            tutorialOverlay.setForceSettingsGeneralTab(() -> {
                // Only force rebuild if not already on Settings > General
                if (panelMode) {
                    if (panelLayout != null && !"Settings".equals(panelLayout.getActiveSection())) {
                        panelLayout.setActiveSection("Settings");
                    }
                } else {
                    if (settingsActiveTab != 0) {
                        settingsActiveTab = 0;
                        buildSettingsWindow();
                        if (settingsWindow != null) settingsWindow.reflowChildren();
                    }
                }
            });
            tutorialOverlay.setLayoutSectionBoundsSupplier(() -> {
                // Calculate region from content panel position using known widget layout:
                // 7 widgets above (keybind label + 6 checkboxes), each 14px + 2px spacing = 16px
                // Tab header = 14px, panel padding = 4px
                // Layout section = 4 widgets (label + dropdown + label + dropdown) in panelMode, 2 in windowMode
                int widgetsAbove = 7;
                int itemH = 16; // 14px height + 2px spacing
                int tabH = 14;
                int pad = 4;
                int sectionItems = panelMode ? 4 : 2; // with/without sidebar position

                if (panelMode && panelLayout != null) {
                    var cp = panelLayout.getContentPanel();
                    if (cp != null) {
                        int topY = cp.getY() + tabH + pad + widgetsAbove * itemH - 4;
                        int sectionH = sectionItems * itemH + 8;
                        int leftX = cp.getX() + pad;
                        int w = 220;
                        return new int[]{leftX, topY, w, sectionH};
                    }
                } else if (settingsWindow != null) {
                    // Window mode: title bar 16px + tab 14px + padding
                    int topY = settingsWindow.getY() + 16 + tabH + pad + widgetsAbove * itemH - 4;
                    int sectionH = sectionItems * itemH + 8;
                    int leftX = settingsWindow.getX() + pad;
                    int w = 220;
                    return new int[]{leftX, topY, w, sectionH};
                }
                return null;
            });
        }

        initialized = true;
        closedIntentionally = false;
    }

    private boolean isScreenAnimating() {
        return screenAnimState != ScreenAnimState.NONE;
    }

    private float screenEaseIn(float t) {
        return t * t;
    }

    private float screenEaseOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    private DraggableWindow createWindow(String id, String title, int defX, int defY, int defW, int defH) {
        DraggableWindow w = new DraggableWindow(id, title, defX, defY, defW, defH);
        // Apply saved state if available
        if (savedWindowStates != null && savedWindowStates.containsKey(id)) {
            WindowStateManager.WindowState state = savedWindowStates.get(id);
            w.setPosition(state.x(), state.y());
            w.setSize(state.width(), state.height());
            w.setVisible(state.visible());
            w.setMinimized(state.minimized());
        }
        return w;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Window Builders
    // ══════════════════════════════════════════════════════════════════════════

    private void buildServerInfoWindow() {
        serverInfoWindow.clearChildren();
        Minecraft mc = Minecraft.getInstance();
        ColorScheme cs = ColorScheme.get();

        if (session == null) {
            serverInfoWindow.addChild(new Label(0, 0, 280, "Not connected to a server.", cs.textSecondary()));
            serverInfoWindow.addChild(new Label(0, 0, 280, ""));
            serverInfoWindow.addChild(new Button(0, 0, 100, "See Report", () -> { reportMode = true; reportScroll = 0; }));
            return;
        }

        // ── Connection section (collapsible) ──
        CollapsibleSection connSection = new CollapsibleSection(0, 0, 280, "Connection");
        connSection.addChild(new Label(0, 0, 280, "IP: " + session.getIp(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 280, "Port: " + session.getPort(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 280, "Domain: " + session.getDomain(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 280, "Version: " + session.getVersion(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 280, "Brand: " + session.getBrand(), cs.textPrimary()));
        if (!session.getServerSoftware().isEmpty()) {
            connSection.addChild(new Label(0, 0, 280, "Software: " + session.getServerSoftware(), cs.textPrimary()));
        }
        connSection.addChild(new Label(0, 0, 280, "Players: " + session.getPlayerCount(), cs.textPrimary()));
        serverInfoWindow.addChild(connSection);

        // ── World section (collapsible) ──
        CollapsibleSection worldSection = new CollapsibleSection(0, 0, 280, "World");
        worldSection.addChild(new Label(0, 0, 280, "Dimension: " + (session != null ? session.getDimension() : "N/A"), cs.textPrimary()));
        if (mc.level != null) {
            worldSection.addChild(new Label(0, 0, 280, "Difficulty: " + mc.level.getDifficulty().name(), cs.textPrimary()));
            worldSection.addChild(new Label(0, 0, 280, "Day Time: " + (mc.level.getDayTime() % 24000), cs.textPrimary()));
            worldSection.addChild(new Label(0, 0, 280, "Raining: " + mc.level.isRaining(), cs.textPrimary()));
            var border = mc.level.getWorldBorder();
            worldSection.addChild(new Label(0, 0, 280, "Border Size: " + String.format("%.0f", border.getSize()), cs.textPrimary()));
        }
        if (mc.gameMode != null) {
            worldSection.addChild(new Label(0, 0, 280, "Gamemode: " + mc.gameMode.getPlayerMode().name(), cs.textPrimary()));
        }
        if (session != null && session.getResourcePack() != null) {
            worldSection.addChild(new Label(0, 0, 280, "Resource Pack: " + session.getResourcePack(), cs.textPrimary()));
        }
        if (mc.getCurrentServer() != null && mc.getCurrentServer().motd != null) {
            worldSection.addChild(new Label(0, 0, 280, "MOTD: " + mc.getCurrentServer().motd.getString(), cs.textSecondary()));
        }
        serverInfoWindow.addChild(worldSection);

        // ── Plugins section (collapsible) ──
        List<String> plugins = new ArrayList<>(session.getPlugins());
        Collections.sort(plugins);
        CollapsibleSection pluginSection = new CollapsibleSection(0, 0, 280, "Plugins (" + plugins.size() + ")");

        ScrollableList pList = new ScrollableList(0, 0, 280, 150);
        pList.setAutoScroll(true);
        for (String p : plugins) {
            pList.addItem(p, cs.eventPlugin());
        }
        pList.setOnRightClick((item, idx, mx, my) -> {
            ContextMenu menu = new ContextMenu(mx, my);
            menu.addItem("Copy Name", () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(item.text);
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied: " + item.text));
            });
            PopupLayer.open(menu, () -> new int[]{mx, my}, null);
        });
        pluginSection.addChild(pList);

        TextField search = persistentTextField("window_plugin_search", 0, 0, 200, "Filter plugins...");
        search.setOnChange(text -> {
            savedTextFields.put("window_plugin_search", text);
            pList.clearItems();
            String q = text.toLowerCase(Locale.ROOT).trim();
            List<String> allPlugins = session != null ? new ArrayList<>(session.getPlugins()) : Collections.emptyList();
            Collections.sort(allPlugins);
            for (String p : allPlugins) {
                if (q.isEmpty() || p.toLowerCase(Locale.ROOT).contains(q)) {
                    pList.addItem(p, cs.eventPlugin());
                }
            }
        });
        pluginSection.addChild(search);

        if (plugins.isEmpty()) {
            pluginSection.addChild(new Label(0, 0, 280, "No plugins detected yet.", cs.textSecondary()));
        }

        pluginSection.addChild(new Button(0, 0, 100, "Copy All", () -> {
            List<String> all = session != null ? new ArrayList<>(session.getPlugins()) : new ArrayList<>();
            Set<String> unique = new LinkedHashSet<>(all);
            Minecraft.getInstance().keyboardHandler.setClipboard(String.join(", ", unique));
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied to clipboard"));
        }));

        serverInfoWindow.addChild(pluginSection);

        // ── Buttons (anchored to bottom) ──
        Button reportBtn = new Button(0, 0, 100, "See Report", () -> {
            reportMode = true;
            reportScroll = 0;
        });
        reportBtn.setTooltip("View detailed detection report");
        reportBtn.setAnchor(Widget.Anchor.BOTTOM);
        reportBtn.setFixedHeight(14);
        reportBtn.setMargins(0, 105, 0, 0);
        serverInfoWindow.addChild(reportBtn);

        Button exportBtn = new Button(0, 0, 100, "Export", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String json = LogExporter.exportJson(data, eventBus.getEvents());
            try {
                Path path = LogExporter.saveToFile(json, "json");
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
            } catch (IOException e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
            }
        });
        exportBtn.setTooltip("Export server data as JSON");
        exportBtn.setAnchor(Widget.Anchor.BOTTOM_RIGHT);
        exportBtn.setFixedWidth(100);
        exportBtn.setFixedHeight(14);
        serverInfoWindow.addChild(exportBtn);
    }


    private void buildConsoleWindow() {
        consoleWindow.clearChildren();

        // Session timeline bar at the top
        TimelineBar timeline = new TimelineBar(0, 0, 260, 14);
        timeline.setAnchor(Widget.Anchor.TOP);
        timeline.setFixedHeight(14);
        timeline.setMargins(4, 0, 0, 0);
        timeline.setEvents(eventBus.getEvents());
        consoleWindow.addChild(timeline);

        // Shared output list (connection log events + command output)
        consoleOutput = new ScrollableList(0, 0, 280, 140);
        consoleOutput.setAnchor(Widget.Anchor.FILL_ABOVE);
        consoleOutput.setMargins(20, 0, 18, 0); // 20px top for timeline, 18px bottom for input
        consoleOutput.setAutoScroll(true);

        // Populate with existing events
        for (LogEvent event : eventBus.getEvents()) {
            consoleOutput.addItem(event.toString(), ColorScheme.get().eventColor(event.getType()));
        }
        lastEventCount = eventBus.getEvents().size();

        // Welcome message
        consoleOutput.addItem("Type \"help\" for available commands.", ColorScheme.get().textSecondary());

        consoleOutput.setOnRightClick((item, idx, mx, my) -> {
            ContextMenu menu = new ContextMenu(mx, my);
            menu.addItem("Copy Line", () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(item.text);
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied line to clipboard"));
            });
            PopupLayer.open(menu, () -> new int[]{mx, my}, null);
        });

        consoleWindow.addChild(consoleOutput);

        // Input field — bottom, leaves room on right for send button
        consoleInput = persistentTextField("console_input", 0, 0, 240, "Type command...");
        consoleInput.setAnchor(Widget.Anchor.BOTTOM);
        consoleInput.setFixedHeight(14);
        consoleInput.setMargins(0, 55, 0, 0); // 55px right margin for send button
        consoleWindow.addChild(consoleInput);

        Button sendBtn = new Button(0, 0, 100, "Send", this::submitConsoleCommand).setTooltip("Execute command on server");
        sendBtn.setAnchor(Widget.Anchor.BOTTOM_RIGHT);
        sendBtn.setFixedWidth(50);
        sendBtn.setFixedHeight(14);
        consoleWindow.addChild(sendBtn);
    }

    private void submitConsoleCommand() {
        if (consoleInput == null) return;
        String input = consoleInput.getText().trim();
        if (input.isEmpty()) return;

        // Post command input and output through EventBus so they survive screen rebuilds
        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "> " + input));

        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod != null && mod.getCommandRegistry() != null) {
            Consumer<String> output = line -> eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, line));
            mod.getCommandRegistry().dispatch(input, output);
        } else {
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Command system not available."));
        }

        consoleInput.clear();
        clearSavedTextField("console_input");
    }

    private void buildSettingsWindow() {
        saveActiveTab(settingsWindow, v -> settingsActiveTab = v);
        settingsWindow.clearChildren();

        TabContainer tabs = new TabContainer(0, 0, 210, 260);
        tabs.setAnchor(Widget.Anchor.FILL);

        // ── General Tab ─────────────────────────────────────────────────────
        Panel generalTab = tabs.addTab("General");
        generalTab.addChild(new Label(0, 0, 200, "Keybind: Z (rebind in Options > Controls)", ColorScheme.get().textSecondary()));

        generalTab.addChild(new CheckBox(0, 0, 200, "Passive GUI Detection",
                config.passiveGuiDetection,
                v -> { config.passiveGuiDetection = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 200, "Show HUD summary",
                config.showHudSummary,
                v -> { config.showHudSummary = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 200, "Show scan overlay",
                config.showScanOverlay,
                v -> { config.showScanOverlay = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 200, "GUI Animations",
                config.guiAnimations,
                v -> {
                    config.guiAnimations = v;
                    ConfigManager.save(config);
                    DraggableWindow.animationsEnabled = v;
                }));
        generalTab.addChild(new CheckBox(0, 0, 200, "Taskbar Slide Animation",
                config.taskbarSlideAnimation,
                v -> { config.taskbarSlideAnimation = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 200, "Auto-handle resource packs",
                config.autoHandleResourcePacks,
                v -> { config.autoHandleResourcePacks = v; ConfigManager.save(config); }));

        // Taskbar position (only relevant in windows mode)
        if ("windows".equals(layoutMode)) {
            generalTab.addChild(new Label(0, 0, 200, "Taskbar position:", ColorScheme.get().textSecondary()));
            generalTab.addChild(new Dropdown(0, 0, 200, "",
                    List.of("bottom", "top", "left"),
                    config.taskbarPosition != null ? config.taskbarPosition : "bottom",
                    v -> {
                        config.taskbarPosition = v;
                        ConfigManager.save(config);
                        needsRebuild = true;
                    }));
        }

        // Layout mode dropdown
        Label layoutModeLabel = new Label(0, 0, 200, "Layout mode:", ColorScheme.get().textSecondary());
        layoutSectionFirst = layoutModeLabel;
        generalTab.addChild(layoutModeLabel);
        String currentLayout = config.layoutMode != null ? config.layoutMode : "windows";
        if ("panel".equals(currentLayout)) currentLayout = "dynamic"; // backwards compat
        String displayLayout = currentLayout.substring(0, 1).toUpperCase() + currentLayout.substring(1);
        Dropdown layoutDropdown = new Dropdown(0, 0, 200, "",
                List.of("Windows", "Dynamic"),
                displayLayout,
                v -> {
                    config.layoutMode = v.toLowerCase();
                    ConfigManager.save(config);
                    needsRebuild = true;
                });
        layoutSectionLast = layoutDropdown;
        generalTab.addChild(layoutDropdown);

        // Sidebar position (only in dynamic mode)
        if (panelMode) {
            generalTab.addChild(new Label(0, 0, 200, "Sidebar position:", ColorScheme.get().textSecondary()));
            String currentSide = config.sidebarPosition != null ? config.sidebarPosition : "left";
            String displaySide = currentSide.substring(0, 1).toUpperCase() + currentSide.substring(1);
            Dropdown sidebarDropdown = new Dropdown(0, 0, 200, "",
                    List.of("Left", "Right"),
                    displaySide,
                    v -> {
                        config.sidebarPosition = v.toLowerCase();
                        ConfigManager.save(config);
                        if (panelLayout != null) {
                            panelLayout.setSidebarSide(v.toLowerCase());
                        }
                    });
            layoutSectionLast = sidebarDropdown;
            generalTab.addChild(sidebarDropdown);
        }

        generalTab.addChild(new Label(0, 0, 200, ""));
        generalTab.addChild(new Button(0, 0, 150, "Reset Window Positions", () -> {
            init();
        }));

        // ── Theme Tab (live preview) ───────────────────────────────────────
        Panel themeTab = tabs.addTab("Theme");
        themeTab.addChild(new Label(0, 0, 200, "Select a theme:", ColorScheme.get().textSecondary()));

        Map<String, ColorScheme> availableThemes = ThemeManager.getInstance().getThemes();
        List<String> themeNames = new ArrayList<>(availableThemes.keySet());

        String currentThemeName = ColorScheme.get().name().toLowerCase(Locale.ROOT);
        Dropdown themeDropdown = new Dropdown(0, 0, 200, "",
                themeNames, currentThemeName,
                v -> {
                    ColorScheme theme = ThemeManager.getInstance().getTheme(v);
                    if (theme != null) {
                        ColorScheme.setActive(theme);
                        config.activeTheme = theme.name();
                        ConfigManager.save(config);
                        needsRebuild = true; // Deferred — can't call init() mid-callback
                    }
                });
        themeTab.addChild(themeDropdown);

        themeTab.addChild(new Label(0, 0, 200, "Current: " + ColorScheme.get().name(), ColorScheme.get().accent()));

        themeTab.addChild(new CheckBox(0, 0, 200, "Enable gradients", config.gradientsEnabled, v -> {
            config.gradientsEnabled = v;
            ColorScheme.setGradientsEnabled(v);
            ConfigManager.save(config);
        }));

        themeTab.addChild(new CheckBox(0, 0, 200, "Background gradient overlay", config.backgroundGradientEnabled, v -> {
            config.backgroundGradientEnabled = v;
            ColorScheme.setBackgroundGradientEnabled(v);
            ConfigManager.save(config);
        }));


        /* ── Theme Creator (disabled for now) ──────────────────────────────────
        themeTab.addChild(new Label(0, 0, 200, ""));
        themeTab.addChild(new Label(0, 0, 200, "\u258C Create Theme", ColorScheme.get().accent()));
        themeCreatorPanel = new ThemeCreatorPanel(0, 0, 250);
        themeTab.addChild(themeCreatorPanel);
        */

        // ── Export Tab ──────────────────────────────────────────────────────
        Panel exportTab = tabs.addTab("Export");
        exportTab.addChild(new Label(0, 0, 200, "Export current data:", ColorScheme.get().textSecondary()));

        exportTab.addChild(new Button(0, 0, 100, "Export JSON", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String json = LogExporter.exportJson(data, eventBus.getEvents());
            try {
                Path path = LogExporter.saveToFile(json, "json");
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
                openExportFolder();
            } catch (IOException e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
            }
        }));
        exportTab.addChild(new Button(0, 0, 100, "Export CSV", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String csv = LogExporter.exportCsv(data, eventBus.getEvents());
            try {
                Path path = LogExporter.saveToFile(csv, "csv");
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
                openExportFolder();
            } catch (IOException e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
            }
        }));
        exportTab.addChild(new Button(0, 0, 100, "Copy to Clipboard", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String text = LogExporter.exportClipboard(data, eventBus.getEvents());
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied to clipboard"));
        }));

        // ── API Tab ──────────────────────────────────────────────────────
        Panel apiTab = tabs.addTab("API");
        com.archivist.api.ApiGuiHelper.build(apiTab, config, session,
                ArchivistMod.getInstance() != null ? ArchivistMod.getInstance().getApiSyncManager() : null,
                eventBus, this::buildSettingsWindow);

        // ── Rules Tab ───────────────────────────────────────────────────
        Panel rulesTab = tabs.addTab("Rules");
        var ruleDb = ArchivistMod.getInstance() != null ? ArchivistMod.getInstance().getGuiRuleDatabase() : null;
        int ruleCount = ruleDb != null ? ruleDb.getAll().size() : 0;
        rulesTab.addChild(new Label(0, 0, 200, "Rules (" + ruleCount + ")", ColorScheme.get().textSecondary()));

        if (ruleDb != null) {
            var allRules = ruleDb.getAll();
            ScrollableList ruleList = new ScrollableList(0, 0, 200, 140);
            ruleList.setAnchor(Widget.Anchor.FILL_ABOVE);
            ruleList.setMargins(0, 0, 30, 0);
            rulesListRef = ruleList;

            if (allRules.isEmpty()) {
                ruleList.addItem("No rules saved yet.", ColorScheme.get().textSecondary());
                ruleList.addItem("Use X on a container to", ColorScheme.get().textSecondary());
                ruleList.addItem("capture a GUI rule.", ColorScheme.get().textSecondary());
            } else {
                for (var rule : allRules) {
                    String label = rule.pluginName() + " (" + rule.patterns().size() + " pattern"
                            + (rule.patterns().size() != 1 ? "s" : "") + ")";
                    ruleList.addItem(label, ColorScheme.get().textPrimary());
                }

                ruleList.setOnRightClick((item, idx, mx, my) -> {
                    if (idx < 0 || idx >= allRules.size()) return;
                    var rule = allRules.get(idx);
                    ContextMenu menu = new ContextMenu(mx, my);
                    menu.addItem("Delete", () -> {
                        ruleDb.delete(rule.pluginId());
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Deleted rule: " + rule.pluginName()));
                        buildSettingsWindow();
                        settingsWindow.reflowChildren();
                    });
                    PopupLayer.open(menu, () -> new int[]{mx, my}, null);
                });
            }

            // Delete shortcut support
            deleteSelectedRule = () -> {
                int idx = ruleList.getSelectedIndex();
                if (idx >= 0 && idx < allRules.size()) {
                    var rule = allRules.get(idx);
                    ruleDb.delete(rule.pluginId());
                    eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Deleted rule: " + rule.pluginName()));
                    buildSettingsWindow();
                    settingsWindow.reflowChildren();
                }
            };

            rulesTab.addChild(ruleList);

            rulesTab.addChild(new Button(0, 0, 150, "Reload Rules", () -> {
                ruleDb.reload();
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Reloaded " + ruleDb.getAll().size() + " rules"));
                buildSettingsWindow();
                settingsWindow.reflowChildren();
            }));
        }

        tabs.setActiveTab(settingsActiveTab);
        settingsWindow.addChild(tabs);
    }

    private void buildInspectorWindow() {
        inspectorWindow.clearChildren();

        // Count inspector items for title
        int inspectorItemCount = 0;
        ArchivistMod modCount = ArchivistMod.getInstance();
        GuiCapture countCapture = (modCount != null && modCount.getRuleCaptureManager() != null)
                ? modCount.getRuleCaptureManager().getLastCapture() : null;
        if (countCapture != null) inspectorItemCount = countCapture.items().size();
        inspectorWindow.setTitle("Inspector (" + inspectorItemCount + " items)");

        // ScrollableList fills space above the buttons (bottom margin leaves room for 2 buttons)
        inspectorList = new ScrollableList(0, 0, 230, 170);
        inspectorList.setAnchor(Widget.Anchor.FILL_ABOVE);
        inspectorList.setMargins(0, 0, 32, 0);

        // Show last captured GUI data
        ArchivistMod mod2 = ArchivistMod.getInstance();
        GuiCapture lastCapture = (mod2 != null && mod2.getRuleCaptureManager() != null)
                ? mod2.getRuleCaptureManager().getLastCapture() : null;
        if (lastCapture != null) {
            inspectorList.addItem("Last Capture:", ColorScheme.get().accent());
            inspectorList.addItem("  Title: " + lastCapture.title(), ColorScheme.get().textPrimary());
            inspectorList.addItem("  Type: " + lastCapture.containerType(), ColorScheme.get().textSecondary());
            inspectorList.addItem("  Items: " + lastCapture.items().size(), ColorScheme.get().textSecondary());
            inspectorList.addItem("  Time: " + lastCapture.timestamp(), ColorScheme.get().textSecondary());
            inspectorList.addItem("", 0);

            // Show fingerprint matches
            ArchivistMod mod = ArchivistMod.getInstance();

            // GUI rule matches
            if (mod != null && mod.getGuiRuleMatcher() != null && mod.getGuiRuleDatabase() != null) {
                var ruleMatches = mod.getGuiRuleMatcher().match(lastCapture, mod.getGuiRuleDatabase().getAll());
                if (!ruleMatches.isEmpty()) {
                    inspectorList.addItem("Rule Matches:", ColorScheme.get().accent());
                    for (var match : ruleMatches) {
                        inspectorList.addItem("  " + match.rule().pluginName()
                                        + " (" + String.format("%.0f%%", match.confidence() * 100) + ")",
                                ColorScheme.get().eventPlugin());
                        inspectorList.addItem("    Pattern: " + match.pattern().label()
                                        + " (" + match.matchedMarkers() + "/" + match.totalMarkers() + ")",
                                ColorScheme.get().textSecondary());
                    }
                }
            }

            // No rule matches message
            if (mod != null && mod.getGuiRuleMatcher() != null && mod.getGuiRuleDatabase() != null) {
                var ruleCheck = mod.getGuiRuleMatcher().match(lastCapture, mod.getGuiRuleDatabase().getAll());
                if (ruleCheck.isEmpty()) {
                    inspectorList.addItem("No rule matches.", ColorScheme.get().textSecondary());
                }
            }

            inspectorList.addItem("", 0);
            // Show items
            inspectorList.addItem("Items:", ColorScheme.get().accent());
            for (CapturedItem item : lastCapture.items()) {
                inspectorList.addItem("  [" + item.slot() + "] " + item.displayName(), ColorScheme.get().textPrimary());
                inspectorList.addItem("    " + item.materialId() + " x" + item.count(), ColorScheme.get().textSecondary());
                for (String lore : item.lore()) {
                    inspectorList.addItem("    " + lore, ColorScheme.get().textSecondary());
                }
            }
        } else {
            inspectorList.addItem("No GUI captured yet.", ColorScheme.get().textSecondary());
            inspectorList.addItem("Open a server inventory to", ColorScheme.get().textSecondary());
            inspectorList.addItem("capture GUI data.", ColorScheme.get().textSecondary());
        }

        inspectorWindow.addChild(inspectorList);

        // Buttons at the bottom (BOTTOM anchor positions them from the bottom edge)
        refreshInspector = () -> { buildInspectorWindow(); inspectorWindow.reflowChildren(); };
        Button refreshBtn = new Button(0, 0, 100, "Refresh", refreshInspector);
        refreshBtn.setAnchor(Widget.Anchor.BOTTOM);
        refreshBtn.setFixedHeight(14);
        refreshBtn.setTooltip("Refresh inspector data");
        refreshBtn.setMargins(0, 0, 0, 0);
        inspectorWindow.addChild(refreshBtn);
    }

    private void buildServerListWindow() {
        serverListWindow.clearChildren();

        List<ServerLogData> logs = JsonLogger.readAllLogs();
        serverListWindow.setTitle("Server List (" + logs.size() + ")");

        // Create split pane: left = server list, right = detail panel
        SplitPane splitPane = new SplitPane(0, 0, 380, 310);
        splitPane.setAnchor(Widget.Anchor.FILL);
        splitPane.setSplitRatio(0.4f);

        ServerListPanel slPanel = new ServerListPanel(0, 0, 380, 310);

        // Detail panel (right side) — starts with empty state
        Panel detailPanel = new Panel(0, 0, 240, 310);
        detailPanel.addChild(new Label(0, 0, 200, "Click a server to view details.", ColorScheme.get().textSecondary()));

        splitPane.setLeft(slPanel);
        splitPane.setRight(detailPanel);
        splitPane.setRightVisible(false);

        for (ServerLogData log : logs) {
            String displayName = getLogDisplayName(log);
            slPanel.addServer(
                    displayName,
                    log.serverInfo().version(),
                    log.serverInfo().brand(),
                    log.plugins().size(),
                    log.worlds().size(),
                    log.timestamp()
            );
        }

        slPanel.setOnServerSelected(addr -> {
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Selected: " + addr));
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    populateDetailPanel(detailPanel, log, logs,
                            () -> { buildServerListWindow(); serverListWindow.reflowChildren(); });
                    splitPane.setRightVisible(true);
                    break;
                }
            }
        });

        slPanel.setOnViewDetails(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    showServerLogDetail(log);
                    break;
                }
            }
        });

        slPanel.setOnExportServer(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    String json = LogExporter.exportJson(log, List.of());
                    try {
                        Path path = LogExporter.saveToFile(json, "json");
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
                    } catch (IOException e) {
                        eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
                    }
                    break;
                }
            }
        });

        slPanel.setOnQuickConnect(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    String connectAddr = !"unknown".equals(log.serverInfo().domain())
                            ? log.serverInfo().domain()
                            : log.serverInfo().ip();
                    if (log.serverInfo().port() != 25565) {
                        connectAddr += ":" + log.serverInfo().port();
                    }
                    final String serverAddr = connectAddr;
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        try {
                            net.minecraft.client.multiplayer.ServerData serverData =
                                    new net.minecraft.client.multiplayer.ServerData(addr, serverAddr,
                                            net.minecraft.client.multiplayer.ServerData.Type.OTHER);
                            net.minecraft.client.multiplayer.resolver.ServerAddress parsed =
                                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(serverAddr);
                            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                                    mc.screen, mc, parsed, serverData, false, null);
                        } catch (Exception e) {
                            eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Quick-connect failed: " + e.getMessage()));
                        }
                    });
                    break;
                }
            }
        });

        slPanel.setOnDeleteServer(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    try {
                        Path logDir = JsonLogger.getLogsDirectory();
                        String filename = JsonLogger.getLogFile(log);
                        Files.deleteIfExists(logDir.resolve(filename));
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Deleted: " + filename));
                        buildServerListWindow(); // rebuild
                    } catch (Exception e) {
                        eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Delete failed: " + e.getMessage()));
                    }
                    break;
                }
            }
        });

        // Empty state check
        if (logs.isEmpty()) {
            serverListWindow.addChild(new Label(0, 0, 300, "No server logs found.", ColorScheme.get().textSecondary()));
        } else {
            serverListWindow.addChild(splitPane);
        }

        // Store refresh runnable for keyboard shortcut
        refreshLogs = () -> { buildServerListWindow(); serverListWindow.reflowChildren(); };
    }

    // Manual Log: stored previous values for undo
    private String manualLogPrevIp;
    private String manualLogPrevDomain;
    private int manualLogPrevPort;
    private boolean manualLogHasUndo = false;

    private void buildManualLogWindow() {
        manualLogWindow.clearChildren();

        manualLogWindow.addChild(new Label(0, 0, 200, "Override server details:"));

        TextField ipField = persistentTextField("manual_ip", 0, 0, 200, "IP (leave blank to keep)");
        TextField domainField = persistentTextField("manual_domain", 0, 0, 200, "Domain (leave blank to keep)");
        TextField portField = persistentTextField("manual_port", 0, 0, 200, "Port (leave blank to keep)");
        portField.setOnChange(text -> {
            savedTextFields.put("manual_port", text);
            try {
                int port = Integer.parseInt(text);
                portField.setError(port < 1 || port > 65535);
                portField.setErrorTooltip(port < 1 || port > 65535 ? "Port must be 1-65535" : null);
            } catch (NumberFormatException e) {
                portField.setError(!text.isEmpty());
                portField.setErrorTooltip(text.isEmpty() ? null : "Port must be a number (1-65535)");
            }
        });

        manualLogWindow.addChild(ipField);
        manualLogWindow.addChild(domainField);
        manualLogWindow.addChild(portField);

        Button applyBtn = new Button(0, 0, 200, "Apply & Re-log", () -> {
            if (session == null) return;
            // Save current values for undo
            manualLogPrevIp = session.getIp();
            manualLogPrevDomain = session.getDomain();
            manualLogPrevPort = session.getPort();
            manualLogHasUndo = true;
            // Apply overrides
            String newIp = ipField.getText().trim();
            String newDomain = domainField.getText().trim();
            String newPort = portField.getText().trim();
            if (!newIp.isEmpty()) session.setIp(newIp);
            if (!newDomain.isEmpty()) session.setDomain(newDomain);
            if (!newPort.isEmpty()) {
                try { session.setPort(Integer.parseInt(newPort)); }
                catch (NumberFormatException ignored) {}
            }
            JsonLogger.writeLog(session.toLogData());
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM,
                    "[MANUAL] Server details updated \u2192 " + session.getDomain() + " (" + session.getIp() + ":" + session.getPort() + ")"));
            buildServerInfoWindow();
            buildManualLogWindow();
            ipField.clear(); clearSavedTextField("manual_ip");
            domainField.clear(); clearSavedTextField("manual_domain");
            portField.clear(); clearSavedTextField("manual_port");
        });
        applyBtn.setAnchor(Widget.Anchor.BOTTOM);
        applyBtn.setFixedHeight(14);
        applyBtn.setMargins(0, 105, 0, 0);
        manualLogWindow.addChild(applyBtn);

        Button undoBtn = new Button(0, 0, 100, "Undo", () -> {
            if (session == null || !manualLogHasUndo) return;
            session.setIp(manualLogPrevIp);
            session.setDomain(manualLogPrevDomain);
            session.setPort(manualLogPrevPort);
            manualLogHasUndo = false;
            JsonLogger.writeLog(session.toLogData());
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM,
                    "[MANUAL] Reverted \u2192 " + session.getDomain() + " (" + session.getIp() + ":" + session.getPort() + ")"));
            buildServerInfoWindow();
            buildManualLogWindow();
        });
        undoBtn.setEnabled(manualLogHasUndo);
        undoBtn.setAnchor(Widget.Anchor.BOTTOM_RIGHT);
        undoBtn.setFixedWidth(100);
        undoBtn.setFixedHeight(14);
        manualLogWindow.addChild(undoBtn);
    }

    private void buildManualLogPanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();
        Runnable rebuild = () -> { if (panelLayout != null) panelLayout.setActiveSection("Manual Log"); };

        panel.addChild(new Label(0, 0, 400, "\u258C Override Server Details", cs.accent()));

        TextField ipField = persistentTextField("manual_ip", 0, 0, 400, "IP (leave blank to keep)");
        TextField domainField = persistentTextField("manual_domain", 0, 0, 400, "Domain (leave blank to keep)");
        TextField portField = persistentTextField("manual_port", 0, 0, 400, "Port (leave blank to keep)");
        portField.setOnChange(text -> {
            savedTextFields.put("manual_port", text);
            try {
                int port = Integer.parseInt(text);
                portField.setError(port < 1 || port > 65535);
            } catch (NumberFormatException e) {
                portField.setError(!text.isEmpty());
            }
        });

        panel.addChild(ipField);
        panel.addChild(domainField);
        panel.addChild(portField);

        panel.addChild(new Label(0, 0, 400, ""));

        panel.addChild(new Button(0, 0, 400, "Apply & Re-log", () -> {
            if (session == null) return;
            manualLogPrevIp = session.getIp();
            manualLogPrevDomain = session.getDomain();
            manualLogPrevPort = session.getPort();
            manualLogHasUndo = true;
            String newIp = ipField.getText().trim();
            String newDomain = domainField.getText().trim();
            String newPort = portField.getText().trim();
            if (!newIp.isEmpty()) session.setIp(newIp);
            if (!newDomain.isEmpty()) session.setDomain(newDomain);
            if (!newPort.isEmpty()) {
                try { session.setPort(Integer.parseInt(newPort)); }
                catch (NumberFormatException ignored) {}
            }
            JsonLogger.writeLog(session.toLogData());
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM,
                    "[MANUAL] Server details updated \u2192 " + session.getDomain() + " (" + session.getIp() + ":" + session.getPort() + ")"));
            ipField.clear(); clearSavedTextField("manual_ip");
            domainField.clear(); clearSavedTextField("manual_domain");
            portField.clear(); clearSavedTextField("manual_port");
            rebuild.run();
        }));

        panel.addChild(new Button(0, 0, 400, "Undo", () -> {
            if (session == null || !manualLogHasUndo) return;
            session.setIp(manualLogPrevIp);
            session.setDomain(manualLogPrevDomain);
            session.setPort(manualLogPrevPort);
            manualLogHasUndo = false;
            JsonLogger.writeLog(session.toLogData());
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM,
                    "[MANUAL] Reverted \u2192 " + session.getDomain() + " (" + session.getIp() + ":" + session.getPort() + ")"));
            rebuild.run();
        }));
    }

    // Account Manager OAuth status messages
    private final List<String> accountStatusMessages = new ArrayList<>();

    private void buildAccountManagerWindow() {
        accountManagerWindow.clearChildren();
        ColorScheme cs = ColorScheme.get();

        ArchivistMod mod = ArchivistMod.getInstance();
        var acctMgr = mod != null ? mod.getAccountManager() : null;

        // Account count in title
        int accountCount = acctMgr != null ? acctMgr.getAccounts().size() : 0;
        accountManagerWindow.setTitle("Accounts (" + accountCount + ")");

        // Refresh button
        refreshAccounts = () -> { buildAccountManagerWindow(); accountManagerWindow.reflowChildren(); };
        accountManagerWindow.addChild(new Button(0, 0, 150, "Refresh", refreshAccounts));

        // Account list (ScrollableList with left-click switch, right-click context menu)
        int listHeight = Math.max(28, accountCount * 12 + 4);
        ScrollableList accountList = new ScrollableList(0, 0, 200, listHeight);
        accountListRef = accountList;

        if (acctMgr != null) {
            var accounts = acctMgr.getAccounts();
            for (var acct : accounts) {
                boolean isActive = acct.id.equals(acctMgr.getActiveAccountId());
                String prefix = isActive ? "\u25B6 " : "  ";
                int color = isActive ? cs.accent() : cs.textPrimary();
                String display = prefix + acct.username + " [" + acct.type.name().toLowerCase() + "]";
                accountList.addItem(new ScrollableList.ListItem(display, color, acct));
            }

            // Left-click = switch account
            accountList.setOnSelect(idx -> {
                var items = accountList.getItems();
                if (idx < 0 || idx >= items.size()) return;
                var entry = (com.archivist.account.AccountEntry) items.get(idx).data;
                if (entry == null || entry.id.equals(acctMgr.getActiveAccountId())) return;
                accountStatusMessages.clear();
                acctMgr.switchAccount(entry.id, msg -> {
                    accountStatusMessages.add(msg);
                    Minecraft.getInstance().execute(this::buildAccountManagerWindow);
                });
                buildAccountManagerWindow();
            });

            // Right-click = context menu
            accountList.setOnRightClick((item, index, mx, my) -> {
                var entry = (com.archivist.account.AccountEntry) item.data;
                if (entry == null) return;
                boolean isActive = entry.id.equals(acctMgr.getActiveAccountId());
                if (isActive && entry.isOriginal) return;
                ContextMenu menu = new ContextMenu(mx, my);
                if (!isActive) {
                    menu.addItem("Switch", () -> {
                        accountStatusMessages.clear();
                        acctMgr.switchAccount(entry.id, msg -> {
                            accountStatusMessages.add(msg);
                            Minecraft.getInstance().execute(this::buildAccountManagerWindow);
                        });
                        buildAccountManagerWindow();
                    });
                }
                if (!entry.isOriginal) {
                    menu.addItem("Delete", () -> {
                        acctMgr.removeAccount(entry.id);
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Removed account"));
                        buildAccountManagerWindow();
                    });
                }
                PopupLayer.open(menu, () -> new int[]{mx, my}, null);
            });
        }

        accountList.setAnchor(Widget.Anchor.FILL_ABOVE);
        accountList.setMargins(16, 0, 0, 0);
        accountManagerWindow.addChild(accountList);

        // Delete shortcut support for accounts
        if (acctMgr != null) {
            final var finalAcctMgr = acctMgr;
            deleteSelectedAccount = () -> {
                int idx = accountList.getSelectedIndex();
                var items = accountList.getItems();
                if (idx >= 0 && idx < items.size()) {
                    var entry = (com.archivist.account.AccountEntry) items.get(idx).data;
                    if (entry != null && !entry.isOriginal) {
                        finalAcctMgr.removeAccount(entry.id);
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Removed account"));
                        buildAccountManagerWindow();
                    }
                }
            };
        }

        // ── Add Account section (anchored to bottom) ──
        if (acctMgr != null) {
            Panel acctBottomPanel = new Panel(0, 0, 200, 0);
            acctBottomPanel.setAnchor(Widget.Anchor.BOTTOM);

            acctBottomPanel.addChild(new Label(0, 0, 200, "\u258C Add Account", cs.accent()));

            List<String> addTypes = List.of("Offline", "Token");
            Dropdown typeDropdown = new Dropdown(0, 0, 150, "",
                    addTypes, accountAddType,
                    v -> {
                        accountAddType = v;
                        buildAccountManagerWindow();
                    });
            acctBottomPanel.addChild(typeDropdown);

            switch (accountAddType) {
                // Microsoft login disabled — needs Azure AD app registration
                case "Offline" -> {
                    TextField crackedName = persistentTextField("acct_offline_name", 0, 0, 200, "Username...");
                    acctBottomPanel.addChild(crackedName);
                    acctBottomPanel.addChild(new Button(0, 0, 150, "Add Offline Account", () -> {
                        String name = crackedName.getText().trim();
                        if (!name.isEmpty()) {
                            acctMgr.addCrackedAccount(name);
                            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Added offline account: " + name));
                            crackedName.clear();
                            clearSavedTextField("acct_offline_name");
                            buildAccountManagerWindow();
                        }
                    }));
                }
                case "Token" -> {
                    TextField tokenField = persistentTextField("acct_token_value", 0, 0, 200, "Access token...");
                    acctBottomPanel.addChild(tokenField);
                    acctBottomPanel.addChild(new Button(0, 0, 150, "Add Token Account", () -> {
                        String token = tokenField.getText().trim();
                        if (!token.isEmpty()) {
                            acctMgr.addTokenAccount("Token Account", token);
                            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Added token account"));
                            tokenField.clear(); clearSavedTextField("acct_token_value");
                            buildAccountManagerWindow();
                        }
                    }));
                }
            }

            // Status messages
            if (!accountStatusMessages.isEmpty()) {
                for (String msg : accountStatusMessages) {
                    acctBottomPanel.addChild(new Label(0, 0, 200, msg, cs.textSecondary()));
                }
            }

            // Calculate height based on child count: each child 14px + 2px spacing + 8px padding
            int acctBottomChildCount = acctBottomPanel.getChildren().size();
            int acctBottomH = acctBottomChildCount * 14 + (acctBottomChildCount - 1) * 2 + 8;
            acctBottomPanel.setFixedHeight(acctBottomH);

            accountManagerWindow.addChild(acctBottomPanel);
        }
    }

    private void buildProxyManagerWindow() {
        proxyManagerWindow.clearChildren();
        ColorScheme cs = ColorScheme.get();

        ArchivistMod mod = ArchivistMod.getInstance();
        var proxyMgr = mod != null ? mod.getProxyManager() : null;

        // Status header
        proxyManagerWindow.addChild(new Label(0, 0, 200, "\u258C Status", cs.accent()));
        if (proxyMgr != null) {
            boolean enabled = proxyMgr.isProxyEnabled();
            var active = proxyMgr.getActiveProfile();
            String statusText = !enabled ? "Disabled" :
                    active.map(p -> "Active: " + p.name + " (" + p.type + ")").orElse("Enabled (no profile selected)");
            int statusColor = enabled && active.isPresent() ? cs.eventConnect() : cs.textSecondary();
            proxyManagerWindow.addChild(new Label(0, 0, 200, "  " + statusText, statusColor));

            proxyManagerWindow.addChild(new CheckBox(0, 0, 200, "Enable Proxy",
                    proxyMgr.isProxyEnabled(),
                    v -> {
                        proxyMgr.setProxyEnabled(v);
                        buildProxyManagerWindow();
                    }));

            // Profiles list
            proxyManagerWindow.addChild(new Label(0, 0, 200, ""));
            proxyManagerWindow.addChild(new Label(0, 0, 200, "\u258C Profiles", cs.accent()));

            var profiles = proxyMgr.getProfiles();
            if (profiles.isEmpty()) {
                proxyManagerWindow.addChild(new Label(0, 0, 200, "  No profiles configured.", cs.textSecondary()));
            } else {
                for (var profile : profiles) {
                    boolean isActive = profile.id.equals(proxyMgr.getActiveProfileId());
                    String prefix = isActive ? "\u25B6 " : "  ";
                    int color = isActive ? cs.accent() : cs.textPrimary();
                    proxyManagerWindow.addChild(new Label(0, 0, 200,
                            prefix + profile.name + " [" + profile.type + "]", color));
                    proxyManagerWindow.addChild(new Label(0, 0, 200,
                            "    " + profile.address + ":" + profile.port, cs.textSecondary()));

                    if (!isActive) {
                        final String id = profile.id;
                        proxyManagerWindow.addChild(new Button(0, 0, 100, "Select", () -> {
                            proxyMgr.setActiveProfile(id);
                            buildProxyManagerWindow();
                        }));
                    }
                    final String removeId = profile.id;
                    proxyManagerWindow.addChild(new Button(0, 0, 100, "Remove", () -> {
                        proxyMgr.removeProfile(removeId);
                        buildProxyManagerWindow();
                    }));
                }
            }

            // Add profile section (anchored to bottom)
            // 8 items: separator + 5 fields + dropdown + button, each 14px + 2px spacing + 8px padding
            int proxyBottomH = 8 * 14 + 7 * 2 + 8;
            Panel proxyBottomPanel = new Panel(0, 0, 200, proxyBottomH);
            proxyBottomPanel.setAnchor(Widget.Anchor.BOTTOM);
            proxyBottomPanel.setFixedHeight(proxyBottomH);

            proxyBottomPanel.addChild(new Label(0, 0, 200, "\u258C Add Profile", cs.accent()));

            TextField nameField = persistentTextField("proxy_name", 0, 0, 200, "Profile name...");
            TextField addrField = persistentTextField("proxy_addr", 0, 0, 200, "Address (e.g. 127.0.0.1)...");
            TextField portField = persistentTextField("proxy_port", 0, 0, 200, "Port (e.g. 1080)...");
            portField.setOnChange(text -> {
                savedTextFields.put("proxy_port", text);
                try {
                    int port = Integer.parseInt(text);
                    portField.setError(port < 1 || port > 65535);
                    portField.setErrorTooltip(port < 1 || port > 65535 ? "Port must be 1-65535" : null);
                } catch (NumberFormatException e) {
                    portField.setError(!text.isEmpty());
                    portField.setErrorTooltip(text.isEmpty() ? null : "Port must be a number (1-65535)");
                }
            });
            TextField userField = persistentTextField("proxy_user", 0, 0, 200, "Username (optional)...");
            TextField passField = persistentTextField("proxy_pass", 0, 0, 200, "Password (optional)...");

            proxyBottomPanel.addChild(nameField);
            proxyBottomPanel.addChild(addrField);
            proxyBottomPanel.addChild(portField);
            proxyBottomPanel.addChild(userField);
            proxyBottomPanel.addChild(passField);

            List<String> typeNames = List.of("SOCKS5", "SOCKS4");
            Dropdown typeDropdown = new Dropdown(0, 0, 150, "",
                    typeNames, "SOCKS5", v -> {});
            proxyBottomPanel.addChild(typeDropdown);

            proxyBottomPanel.addChild(new Button(0, 0, 150, "Add Profile", () -> {
                String name = nameField.getText().trim();
                String addr = addrField.getText().trim();
                String portStr = portField.getText().trim();
                if (name.isEmpty() || addr.isEmpty() || portStr.isEmpty()) return;

                try {
                    int port = Integer.parseInt(portStr);
                    var type = "SOCKS4".equals(typeDropdown.getSelectedValue())
                            ? com.archivist.proxy.ProxyProfile.ProxyType.SOCKS4
                            : com.archivist.proxy.ProxyProfile.ProxyType.SOCKS5;
                    var profile = new com.archivist.proxy.ProxyProfile(name, type, addr, port);
                    profile.username = userField.getText().trim();
                    String pass = passField.getText().trim();
                    if (!pass.isEmpty()) {
                        profile.passwordEncoded = com.archivist.account.SecureStorage.encrypt(pass);
                    }
                    proxyMgr.addProfile(profile);
                    eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Added proxy: " + name));
                    nameField.clear(); clearSavedTextField("proxy_name");
                    addrField.clear(); clearSavedTextField("proxy_addr");
                    portField.clear(); clearSavedTextField("proxy_port");
                    userField.clear(); clearSavedTextField("proxy_user");
                    passField.clear(); clearSavedTextField("proxy_pass");
                    buildProxyManagerWindow();
                } catch (NumberFormatException e) {
                    eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Invalid port number"));
                }
            }));

            proxyManagerWindow.addChild(proxyBottomPanel);

        }
    }

    private void buildAutomationWindow() {
        saveActiveTab(automationWindow, v -> automationActiveTab = v);
        automationWindow.clearChildren();
        ColorScheme cs = ColorScheme.get();
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod == null) return;

        com.archivist.api.automation.SequenceHandler orchestrator = mod.getSequenceHandler();
        com.archivist.api.automation.ScheduleConfig autoConfig = mod.getScheduleConfig();
        com.archivist.api.automation.SequenceState autoState = orchestrator.getState();

        TabContainer tabs = new TabContainer(0, 0, 260, 320);
        tabs.setAnchor(Widget.Anchor.FILL);

        Panel generalTab = tabs.addTab("General");
        buildAutomationGeneralTab(generalTab, orchestrator, autoConfig, autoState, cs, false);

        Panel serversTab = tabs.addTab("Add Servers");
        buildAutomationServersTab(serversTab, orchestrator, autoConfig, autoState, cs, false);

        Panel settingsTab = tabs.addTab("Settings");
        buildAutomationSettingsTab(settingsTab, autoConfig, cs, false);

        tabs.setActiveTab(automationActiveTab);
        automationWindow.addChild(tabs);
    }

    // ── NEW AUTOMATION TABS ────────────────────────────────────────────────

    private void buildAutomationGeneralTab(Panel tab, com.archivist.api.automation.SequenceHandler orch,
                                            com.archivist.api.automation.ScheduleConfig autoConfig,
                                            com.archivist.api.automation.SequenceState state,
                                            ColorScheme cs, boolean isPanelMode) {
        int w = isPanelMode ? 400 : 250;
        Runnable rebuild = isPanelMode
                ? () -> { automationActiveTab = 0; if (panelLayout != null) panelLayout.setActiveSection("Automation"); }
                : this::buildAutomationWindow;

        com.archivist.api.automation.SequenceHandler.Phase phase = orch.getPhase();

        // Server list selector (only when not running)
        java.util.List<String> availableLists = com.archivist.api.automation.AddressListManager.getAvailableLists();
        if (!isRunning(phase) && !availableLists.isEmpty()) {
            tab.addChild(new Dropdown(0, 0, w, "Server List",
                    availableLists, autoConfig.activeServerList,
                    v -> { autoConfig.activeServerList = v; com.archivist.api.automation.ScheduleConfig.save(autoConfig); rebuild.run(); }));
        } else if (isRunning(phase)) {
            tab.addChild(new Label(0, 0, w, "  List: " + autoConfig.activeServerList, cs.textPrimary()));
        }

        // Server count
        java.util.List<String> serverList = orch.getCurrentServerList();
        int total = serverList.isEmpty()
                ? com.archivist.api.automation.AddressListManager.loadList(autoConfig.activeServerList).size()
                : serverList.size();
        int scanned = state.getCompletedCount();
        if (total > 0) {
            tab.addChild(new Label(0, 0, w, "  Servers: " + total + "  |  Scanned: " + scanned, cs.textSecondary()));
        }

        // Scanning: current server
        if (isRunning(phase)) {
            tab.addChild(new Label(0, 0, w, "  Scanning: " + orch.getCurrentServerDisplay(), cs.accent()));
        }

        tab.addChild(new Label(0, 0, w, ""));

        // Buttons
        if (!isRunning(phase)) {
            tab.addChild(new Button(0, 0, w, "Start Scan", () -> { orch.start(); rebuild.run(); }));
        } else {
            if (state.status == com.archivist.api.automation.SequenceState.RunStatus.PAUSED) {
                tab.addChild(new Button(0, 0, w / 2 - 2, "Resume", () -> { orch.resume(); rebuild.run(); }));
            } else {
                tab.addChild(new Button(0, 0, w / 2 - 2, "Pause", () -> { orch.pause(); rebuild.run(); }));
            }
            tab.addChild(new Button(0, 0, w / 2 - 2, "Stop", () -> { orch.stop(); state.reset(); state.save(); rebuild.run(); }));
            tab.addChild(new Button(0, 0, 100, "Skip", () -> { orch.skipCurrent(); rebuild.run(); }));
        }

        // Elapsed timer
        long elapsed = orch.getElapsedMs();
        String elapsedStr = formatElapsed(elapsed);
        automationElapsedLabel = new Label(0, 0, w, "  Elapsed: " + elapsedStr, cs.textSecondary());
        tab.addChild(automationElapsedLabel);

        tab.addChild(new Label(0, 0, w, ""));

        // Status
        int phaseColor = switch (phase) {
            case IDLE, COMPLETED -> cs.textSecondary();
            case CONNECTING, SCANNING, DELAY -> cs.accent();
            case DISCONNECTING -> cs.eventDisconnect();
            case ERROR -> cs.eventError();
        };
        automationStatusLabel = new Label(0, 0, w, "  Status: " + phase.name(), phaseColor);
        tab.addChild(automationStatusLabel);

        if (isRunning(phase) && orch.getEngine() != null) {
            String taskDesc = orch.getEngine().getCurrentTaskDescription();
            if (taskDesc != null && !taskDesc.isEmpty()) {
                tab.addChild(new Label(0, 0, w, "  Task: " + taskDesc, cs.textSecondary()));
            }
        }

        automationProgressLabel = new Label(0, 0, w,
                "  Progress: " + scanned + "/" + total + "  |  Failed: " + state.getFailedCount(), cs.textPrimary());
        tab.addChild(automationProgressLabel);

        // Progress resets automatically when Stop is pressed
    }

    private void buildAutomationServersTab(Panel tab, com.archivist.api.automation.SequenceHandler orch,
                                            com.archivist.api.automation.ScheduleConfig autoConfig,
                                            com.archivist.api.automation.SequenceState autoState,
                                            ColorScheme cs, boolean isPanelMode) {
        int w = isPanelMode ? 400 : 250;
        Runnable rebuild = isPanelMode
                ? () -> { automationActiveTab = 1; if (panelLayout != null) panelLayout.setActiveSection("Automation"); }
                : this::buildAutomationWindow;
        java.util.List<String> availableLists = com.archivist.api.automation.AddressListManager.getAvailableLists();

        // ── Server Lists ──
        tab.addChild(new Label(0, 0, w, "\u258C Server Lists", cs.accent()));

        if (availableLists.isEmpty()) {
            tab.addChild(new Label(0, 0, w, "  No lists yet. Create one below,", cs.textSecondary()));
            tab.addChild(new Label(0, 0, w, "  or drop .txt files into:", cs.textSecondary()));
            tab.addChild(new Label(0, 0, w, "  archivist/automation/server-list/", cs.textSecondary()));
        } else {
            tab.addChild(new Dropdown(0, 0, w, "",
                    availableLists, autoConfig.activeServerList,
                    v -> { autoConfig.activeServerList = v; com.archivist.api.automation.ScheduleConfig.save(autoConfig); rebuild.run(); }));

            if (!autoConfig.activeServerList.isEmpty()) {
                java.util.List<String> servers = com.archivist.api.automation.AddressListManager.loadList(
                        autoConfig.activeServerList);
                tab.addChild(new Label(0, 0, w, "  " + servers.size() + " servers", cs.textSecondary()));

                ScrollableList preview = new ScrollableList(0, 0, w, 120);
                for (String server : servers) {
                    int color;
                    String displayText = server;
                    if (autoState.completedServers.contains(server)) {
                        color = cs.eventConnect();
                    } else if (autoState.failedServers.containsKey(server)) {
                        color = cs.eventError();
                        String reason = autoState.getFailureReason(server);
                        if (reason != null && !reason.isEmpty()) {
                            displayText = server + " \u2014 " + reason;
                        }
                    } else {
                        color = cs.textPrimary();
                    }
                    preview.addItem(displayText, color);
                }
                final java.util.List<String> serversCopy = new java.util.ArrayList<>(servers);
                preview.setOnRightClick((item, idx, mx, my) -> {
                    String address = item.text.contains(" \u2014 ") ? item.text.substring(0, item.text.indexOf(" \u2014 ")) : item.text;
                    ContextMenu menu = new ContextMenu(mx, my);
                    menu.addItem("Copy Address", () -> {
                        Minecraft.getInstance().keyboardHandler.setClipboard(address);
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied: " + address));
                    });
                    menu.addItem("Remove", () -> {
                        serversCopy.remove(address);
                        com.archivist.api.automation.AddressListManager.saveList(autoConfig.activeServerList, serversCopy);
                        rebuild.run();
                    });
                    PopupLayer.open(menu, () -> new int[]{mx, my}, null);
                });
                tab.addChild(preview);
            }
        }

        // ── Create New List ──
        tab.addChild(new Label(0, 0, w, ""));
        tab.addChild(new Label(0, 0, w, "\u258C Create New List", cs.accent()));

        TextField newListName = persistentTextField("auto_new_list", 0, 0, w, "List name...");
        tab.addChild(newListName);

        tab.addChild(new Label(0, 0, w, "  Servers:", cs.textSecondary()));

        for (int i = 0; i < newListServerCount; i++) {
            final int idx = i;
            TextField serverField = persistentTextField("auto_new_list_server_" + i, 0, 0, w, "Server IP or domain...");
            if (i == newListServerCount - 1) {
                serverField.setOnChange(text -> {
                    savedTextFields.put("auto_new_list_server_" + idx, text);
                    if (!text.trim().isEmpty() && idx == newListServerCount - 1) {
                        newListServerCount++;
                        savedFocusedField = "auto_new_list_server_" + idx;
                        savedCursorPos = serverField.getCursorPos();
                        if (isPanelMode) {
                            rebuild.run();
                        } else {
                            tab.clearChildren();
                            buildAutomationServersTab(tab, orch, autoConfig, autoState, cs, false);
                            tab.layoutChildren();
                        }
                    }
                });
            }
            tab.addChild(serverField);
        }

        tab.addChild(new Button(0, 0, w, "Create List", () -> {
            String name = newListName.getText().trim();
            if (!name.isEmpty()) {
                List<String> servers = new ArrayList<>();
                for (int i = 0; i < newListServerCount; i++) {
                    String ip = savedTextFields.getOrDefault("auto_new_list_server_" + i, "").trim();
                    if (!ip.isEmpty()) servers.add(ip);
                }
                com.archivist.api.automation.AddressListManager.saveList(name, servers);
                newListName.clear();
                clearSavedTextField("auto_new_list");
                for (int i = 0; i < newListServerCount; i++) {
                    clearSavedTextField("auto_new_list_server_" + i);
                }
                newListServerCount = 1;
                rebuild.run();
            }
        }));
    }

    private void buildAutomationSettingsTab(Panel tab, com.archivist.api.automation.ScheduleConfig autoConfig,
                                             ColorScheme cs, boolean isPanelMode) {
        int w = isPanelMode ? 400 : 250;

        tab.addChild(new CheckBox(0, 0, w, "NPC fallback if other methods fail",
                autoConfig.npcFallbackEnabled,
                v -> { autoConfig.npcFallbackEnabled = v; com.archivist.api.automation.ScheduleConfig.save(autoConfig); }));

        tab.addChild(new CheckBox(0, 0, w, "Auto-disconnect after each server",
                autoConfig.autoDisconnect,
                v -> { autoConfig.autoDisconnect = v; com.archivist.api.automation.ScheduleConfig.save(autoConfig); }));

        tab.addChild(new CheckBox(0, 0, w, "Shuffle server order",
                autoConfig.shuffleServerList,
                v -> { autoConfig.shuffleServerList = v; com.archivist.api.automation.ScheduleConfig.save(autoConfig); }));

        tab.addChild(new CheckBox(0, 0, w, "Skip already-scanned servers",
                autoConfig.skipAlreadyLogged,
                v -> { autoConfig.skipAlreadyLogged = v; com.archivist.api.automation.ScheduleConfig.save(autoConfig); }));

        tab.addChild(new Label(0, 0, w, ""));

        tab.addChild(new Dropdown(0, 0, w, "On kick",
                java.util.List.of("skip", "retry"), autoConfig.onKickAction,
                v -> { autoConfig.onKickAction = v; com.archivist.api.automation.ScheduleConfig.save(autoConfig); }));

        // Proxy
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod != null) {
            var proxyMgr = mod.getProxyManager();
            java.util.List<String> proxyNames = new java.util.ArrayList<>();
            proxyNames.add("None");
            for (var p : proxyMgr.getProfiles()) proxyNames.add(p.name);

            String currentProxy = "None";
            for (var p : proxyMgr.getProfiles()) {
                if (p.id.equals(autoConfig.proxyProfileId)) { currentProxy = p.name; break; }
            }

            tab.addChild(new Dropdown(0, 0, w, "Proxy",
                    proxyNames, currentProxy,
                    v -> {
                        if ("None".equals(v)) {
                            autoConfig.proxyProfileId = "";
                        } else {
                            for (var p : proxyMgr.getProfiles()) {
                                if (p.name.equals(v)) { autoConfig.proxyProfileId = p.id; break; }
                            }
                        }
                        com.archivist.api.automation.ScheduleConfig.save(autoConfig);
                    }));
        }
    }

    private static boolean isRunning(com.archivist.api.automation.SequenceHandler.Phase phase) {
        return phase != com.archivist.api.automation.SequenceHandler.Phase.IDLE
                && phase != com.archivist.api.automation.SequenceHandler.Phase.COMPLETED;
    }

    private static String formatElapsed(long ms) {
        if (ms <= 0) return "00:00:00";
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        long secs = totalSec % 60;
        if (hours > 0) return hours + "h " + mins + "m " + secs + "s";
        if (mins > 0) return mins + "m " + secs + "s";
        return secs + "s";
    }

    private static String formatLogTimestamp(long epochMs) {
        java.time.LocalDate date = java.time.Instant.ofEpochMilli(epochMs)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return date.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Panel Mode Content Builders
    // ══════════════════════════════════════════════════════════════════════════

    private void addKVToPanel(Panel panel, String key, String value) {
        panel.addChild(new Label(0, 0, 300, key + ": " + value, ColorScheme.get().textPrimary()));
    }

    private void buildServerInfoPanel(Panel panel) {
        Minecraft mc = Minecraft.getInstance();
        ColorScheme cs = ColorScheme.get();

        if (session == null) {
            panel.addChild(new Label(0, 0, 300, "Not connected to a server.", cs.textSecondary()));
            panel.addChild(new Label(0, 0, 300, ""));
            panel.addChild(new Button(0, 0, 100, "See Report", () -> { reportMode = true; reportScroll = 0; }));
            return;
        }

        // ── Connection section (collapsible) ──
        CollapsibleSection connSection = new CollapsibleSection(0, 0, 300, "Connection");
        connSection.addChild(new Label(0, 0, 300, "IP: " + session.getIp(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Port: " + session.getPort(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Domain: " + session.getDomain(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Version: " + session.getVersion(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Brand: " + session.getBrand(), cs.textPrimary()));
        if (!session.getServerSoftware().isEmpty()) {
            connSection.addChild(new Label(0, 0, 300, "Software: " + session.getServerSoftware(), cs.textPrimary()));
        }
        connSection.addChild(new Label(0, 0, 300, "Players: " + session.getPlayerCount(), cs.textPrimary()));
        panel.addChild(connSection);

        // ── World section (collapsible) ──
        CollapsibleSection worldSection = new CollapsibleSection(0, 0, 300, "World");
        worldSection.addChild(new Label(0, 0, 300, "Dimension: " + (session != null ? session.getDimension() : "N/A"), cs.textPrimary()));
        if (mc.level != null) {
            worldSection.addChild(new Label(0, 0, 300, "Difficulty: " + mc.level.getDifficulty().name(), cs.textPrimary()));
            worldSection.addChild(new Label(0, 0, 300, "Day Time: " + (mc.level.getDayTime() % 24000), cs.textPrimary()));
            worldSection.addChild(new Label(0, 0, 300, "Raining: " + mc.level.isRaining(), cs.textPrimary()));
            var border = mc.level.getWorldBorder();
            worldSection.addChild(new Label(0, 0, 300, "Border Size: " + String.format("%.0f", border.getSize()), cs.textPrimary()));
        }
        if (mc.gameMode != null) {
            worldSection.addChild(new Label(0, 0, 300, "Gamemode: " + mc.gameMode.getPlayerMode().name(), cs.textPrimary()));
        }
        if (session != null && session.getResourcePack() != null) {
            worldSection.addChild(new Label(0, 0, 300, "Resource Pack: " + session.getResourcePack(), cs.textPrimary()));
        }
        if (mc.getCurrentServer() != null && mc.getCurrentServer().motd != null) {
            worldSection.addChild(new Label(0, 0, 300, "MOTD: " + mc.getCurrentServer().motd.getString(), cs.textSecondary()));
        }
        panel.addChild(worldSection);

        // ── Plugins section (collapsible) ──
        List<String> plugins = new ArrayList<>(session.getPlugins());
        Collections.sort(plugins);
        CollapsibleSection pluginSection = new CollapsibleSection(0, 0, 300, "Plugins (" + plugins.size() + ")");

        ScrollableList pList = new ScrollableList(0, 0, 300, 150);
        pList.setAutoScroll(true);
        for (String p : plugins) {
            pList.addItem(p, cs.eventPlugin());
        }
        pList.setOnRightClick((item, idx, mx, my) -> {
            ContextMenu menu = new ContextMenu(mx, my);
            menu.addItem("Copy Name", () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(item.text);
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied: " + item.text));
            });
            PopupLayer.open(menu, () -> new int[]{mx, my}, null);
        });
        pluginSection.addChild(pList);

        TextField search = persistentTextField("panel_plugin_search", 0, 0, 300, "Filter plugins...");
        search.setOnChange(text -> {
            savedTextFields.put("panel_plugin_search", text);
            pList.clearItems();
            String q = text.toLowerCase(Locale.ROOT).trim();
            List<String> allPlugins = session != null ? new ArrayList<>(session.getPlugins()) : Collections.emptyList();
            Collections.sort(allPlugins);
            for (String p : allPlugins) {
                if (q.isEmpty() || p.toLowerCase(Locale.ROOT).contains(q)) {
                    pList.addItem(p, cs.eventPlugin());
                }
            }
        });
        pluginSection.addChild(search);

        if (plugins.isEmpty()) {
            pluginSection.addChild(new Label(0, 0, 300, "No plugins detected yet.", cs.textSecondary()));
        }

        pluginSection.addChild(new Button(0, 0, 100, "Copy All", () -> {
            List<String> all = session != null ? new ArrayList<>(session.getPlugins()) : new ArrayList<>();
            Set<String> unique = new LinkedHashSet<>(all);
            Minecraft.getInstance().keyboardHandler.setClipboard(String.join(", ", unique));
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied to clipboard"));
        }));

        panel.addChild(pluginSection);

        // ── Buttons (outside sections) ──
        panel.addChild(new Label(0, 0, 300, ""));
        panel.addChild(new Button(0, 0, 100, "See Report", () -> {
            reportMode = true;
            reportScroll = 0;
        }).setTooltip("View detailed detection report"));
        panel.addChild(new Button(0, 0, 100, "Export", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String json = LogExporter.exportJson(data, eventBus.getEvents());
            try {
                Path path = LogExporter.saveToFile(json, "json");
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
            } catch (IOException e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
            }
        }).setTooltip("Export server data as JSON"));
    }

    private void buildConsolePanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();

        TimelineBar timeline = new TimelineBar(0, 0, 400, 14);
        timeline.setEvents(eventBus.getEvents());
        panel.addChild(timeline);

        consoleOutput = new ScrollableList(0, 0, 400, 300);
        consoleOutput.setAutoScroll(true);
        for (LogEvent event : eventBus.getEvents()) {
            consoleOutput.addItem(event.toString(), cs.eventColor(event.getType()));
        }
        lastEventCount = eventBus.getEvents().size();
        consoleOutput.addItem("Type \"help\" for available commands.", cs.textSecondary());
        consoleOutput.setOnRightClick((item, idx, mx, my) -> {
            ContextMenu menu = new ContextMenu(mx, my);
            menu.addItem("Copy Line", () -> {
                Minecraft.getInstance().keyboardHandler.setClipboard(item.text);
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied line to clipboard"));
            });
            PopupLayer.open(menu, () -> new int[]{mx, my}, null);
        });
        panel.addChild(consoleOutput);

        consoleInput = persistentTextField("console_input", 0, 0, 400, "Type command...");
        panel.addChild(consoleInput);

        panel.addChild(new Button(0, 0, 100, "Send", this::submitConsoleCommand).setTooltip("Execute command on server"));
    }

    private void buildAutomationPanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod == null) return;

        com.archivist.api.automation.SequenceHandler orchestrator = mod.getSequenceHandler();
        com.archivist.api.automation.ScheduleConfig autoConfig = mod.getScheduleConfig();
        com.archivist.api.automation.SequenceState autoState = orchestrator.getState();

        TabContainer tabs = new TabContainer(0, 0, 400, 400);

        Panel generalTab = tabs.addTab("General");
        buildAutomationGeneralTab(generalTab, orchestrator, autoConfig, autoState, cs, true);

        Panel serversTab = tabs.addTab("Add Servers");
        buildAutomationServersTab(serversTab, orchestrator, autoConfig, autoState, cs, true);

        Panel settingsTab = tabs.addTab("Settings");
        buildAutomationSettingsTab(settingsTab, autoConfig, cs, true);

        tabs.setActiveTab(automationActiveTab);
        panel.addChild(tabs);
    }

    private void buildServerListPanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();

        List<ServerLogData> logs = JsonLogger.readAllLogs();

        panel.addChild(new Label(0, 0, 300, "Server List (" + logs.size() + ")", cs.accent()));

        // Create split pane: left = server list, right = detail panel
        SplitPane splitPane = new SplitPane(0, 0, 400, 280);
        splitPane.setAnchor(Widget.Anchor.FILL);
        splitPane.setSplitRatio(0.4f);

        ServerListPanel slPanel = new ServerListPanel(0, 0, 400, 280);

        // Detail panel (right side) — starts with empty state
        Panel detailPanel = new Panel(0, 0, 240, 280);
        detailPanel.addChild(new Label(0, 0, 200, "Click a server to view details.", cs.textSecondary()));

        splitPane.setLeft(slPanel);
        splitPane.setRight(detailPanel);
        splitPane.setRightVisible(false);

        for (ServerLogData log : logs) {
            String displayName = getLogDisplayName(log);
            slPanel.addServer(displayName, log.serverInfo().version(), log.serverInfo().brand(),
                    log.plugins().size(), log.worlds().size(), log.timestamp());
        }

        slPanel.setOnServerSelected(addr -> {
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Selected: " + addr));
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    populateDetailPanel(detailPanel, log, logs,
                            () -> { if (panelLayout != null) panelLayout.setActiveSection("Logs"); });
                    splitPane.setRightVisible(true);
                    break;
                }
            }
        });

        slPanel.setOnViewDetails(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    showServerLogDetail(log);
                    break;
                }
            }
        });

        slPanel.setOnExportServer(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    String json = LogExporter.exportJson(log, List.of());
                    try {
                        Path path = LogExporter.saveToFile(json, "json");
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
                    } catch (IOException e) {
                        eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
                    }
                    break;
                }
            }
        });

        slPanel.setOnQuickConnect(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    String connectAddr = !"unknown".equals(log.serverInfo().domain())
                            ? log.serverInfo().domain()
                            : log.serverInfo().ip();
                    if (log.serverInfo().port() != 25565) {
                        connectAddr += ":" + log.serverInfo().port();
                    }
                    final String serverAddr = connectAddr;
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        try {
                            net.minecraft.client.multiplayer.ServerData serverData =
                                    new net.minecraft.client.multiplayer.ServerData(addr, serverAddr,
                                            net.minecraft.client.multiplayer.ServerData.Type.OTHER);
                            net.minecraft.client.multiplayer.resolver.ServerAddress parsed =
                                    net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(serverAddr);
                            net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                                    mc.screen, mc, parsed, serverData, false, null);
                        } catch (Exception e) {
                            eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Quick-connect failed: " + e.getMessage()));
                        }
                    });
                    break;
                }
            }
        });

        slPanel.setOnDeleteServer(addr -> {
            for (ServerLogData log : logs) {
                if (getLogDisplayName(log).equals(addr)) {
                    try {
                        Path logDir = JsonLogger.getLogsDirectory();
                        String filename = JsonLogger.getLogFile(log);
                        Files.deleteIfExists(logDir.resolve(filename));
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Deleted: " + filename));
                        // Refresh the Logs panel
                        if (panelLayout != null) panelLayout.setActiveSection("Logs");
                    } catch (Exception e) {
                        eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Delete failed: " + e.getMessage()));
                    }
                    break;
                }
            }
        });

        // Empty state
        if (logs.isEmpty()) {
            panel.addChild(new Label(0, 0, 300, "No server logs found.", cs.textSecondary()));
        } else {
            panel.addChild(splitPane);
        }

        panel.addChild(new Button(0, 0, 100, "Refresh", () -> {
            if (panelLayout != null) panelLayout.setActiveSection("Logs");
        }));
    }

    private void buildInspectorPanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();

        // Inspector item count in header
        ArchivistMod modPanelCount = ArchivistMod.getInstance();
        GuiCapture panelCountCapture = (modPanelCount != null && modPanelCount.getRuleCaptureManager() != null)
                ? modPanelCount.getRuleCaptureManager().getLastCapture() : null;
        int panelInspectorCount = panelCountCapture != null ? panelCountCapture.items().size() : 0;
        panel.addChild(new Label(0, 0, 400, "Inspector (" + panelInspectorCount + " items)", cs.accent()));

        ArchivistMod mod2 = ArchivistMod.getInstance();
        GuiCapture lastCapture = (mod2 != null && mod2.getRuleCaptureManager() != null)
                ? mod2.getRuleCaptureManager().getLastCapture() : null;
        int listHeight = lastCapture != null ? 250 : 50;
        inspectorList = new ScrollableList(0, 0, 400, listHeight);
        if (lastCapture != null) {
            inspectorList.addItem("Last Capture:", cs.accent());
            inspectorList.addItem("  Title: " + lastCapture.title(), cs.textPrimary());
            inspectorList.addItem("  Type: " + lastCapture.containerType(), cs.textSecondary());
            inspectorList.addItem("  Items: " + lastCapture.items().size(), cs.textSecondary());
            inspectorList.addItem("  Time: " + lastCapture.timestamp(), cs.textSecondary());
            inspectorList.addItem("", 0);

            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod != null && mod.getGuiRuleMatcher() != null && mod.getGuiRuleDatabase() != null) {
                var ruleMatches = mod.getGuiRuleMatcher().match(lastCapture, mod.getGuiRuleDatabase().getAll());
                if (!ruleMatches.isEmpty()) {
                    inspectorList.addItem("Rule Matches:", cs.accent());
                    for (var match : ruleMatches) {
                        inspectorList.addItem("  " + match.rule().pluginName()
                                        + " (" + String.format("%.0f%%", match.confidence() * 100) + ")",
                                cs.eventPlugin());
                        inspectorList.addItem("    Pattern: " + match.pattern().label()
                                        + " (" + match.matchedMarkers() + "/" + match.totalMarkers() + ")",
                                cs.textSecondary());
                    }
                } else {
                    inspectorList.addItem("No rule matches.", cs.textSecondary());
                }
            }

            inspectorList.addItem("", 0);
            inspectorList.addItem("Items:", cs.accent());
            for (CapturedItem item : lastCapture.items()) {
                inspectorList.addItem("  [" + item.slot() + "] " + item.displayName(), cs.textPrimary());
                inspectorList.addItem("    " + item.materialId() + " x" + item.count(), cs.textSecondary());
                for (String lore : item.lore()) {
                    inspectorList.addItem("    " + lore, cs.textSecondary());
                }
            }
        } else {
            inspectorList.addItem("No GUI captured yet.", cs.textSecondary());
            inspectorList.addItem("Open a server inventory to", cs.textSecondary());
            inspectorList.addItem("capture GUI data.", cs.textSecondary());
        }

        panel.addChild(inspectorList);

        Button refreshBtn = new Button(0, 0, 100, "Refresh", () -> {
            if (panelLayout != null) panelLayout.setActiveSection("Inspector");
        });
        refreshBtn.setTooltip("Reload data");
        panel.addChild(refreshBtn);
    }

    private void buildSettingsPanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();

        TabContainer tabs = new TabContainer(0, 0, 400, 400);

        // ── General Tab ─────────────────────────────────────────────────────
        Panel generalTab = tabs.addTab("General");
        generalTab.addChild(new Label(0, 0, 350, "Keybind: Z (rebind in Options > Controls)", cs.textSecondary()));

        generalTab.addChild(new CheckBox(0, 0, 350, "Passive GUI Detection",
                config.passiveGuiDetection,
                v -> { config.passiveGuiDetection = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 350, "Show HUD summary",
                config.showHudSummary,
                v -> { config.showHudSummary = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 350, "Show scan overlay",
                config.showScanOverlay,
                v -> { config.showScanOverlay = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 350, "GUI Animations",
                config.guiAnimations,
                v -> {
                    config.guiAnimations = v;
                    ConfigManager.save(config);
                    DraggableWindow.animationsEnabled = v;
                }));
        generalTab.addChild(new CheckBox(0, 0, 350, "Taskbar Slide Animation",
                config.taskbarSlideAnimation,
                v -> { config.taskbarSlideAnimation = v; ConfigManager.save(config); }));
        generalTab.addChild(new CheckBox(0, 0, 350, "Auto-handle resource packs",
                config.autoHandleResourcePacks,
                v -> { config.autoHandleResourcePacks = v; ConfigManager.save(config); }));

        // Taskbar position (only relevant in windows mode)
        if ("windows".equals(layoutMode)) {
            generalTab.addChild(new Label(0, 0, 350, "Taskbar position:", cs.textSecondary()));
            Dropdown taskbarPosDropdown = new Dropdown(0, 0, 350, "",
                    List.of("bottom", "top", "left"),
                    config.taskbarPosition != null ? config.taskbarPosition : "bottom",
                    v -> {
                        config.taskbarPosition = v;
                        ConfigManager.save(config);
                        needsRebuild = true;
                    });
            taskbarPosDropdown.setFixedWidth(350);
            generalTab.addChild(taskbarPosDropdown);
        }

        // Layout mode dropdown
        Label layoutModeLabelP = new Label(0, 0, 350, "Layout mode:", cs.textSecondary());
        layoutSectionFirst = layoutModeLabelP;
        generalTab.addChild(layoutModeLabelP);
        String currentLayoutP = config.layoutMode != null ? config.layoutMode : "windows";
        if ("panel".equals(currentLayoutP)) currentLayoutP = "dynamic"; // backwards compat
        String displayLayoutP = currentLayoutP.substring(0, 1).toUpperCase() + currentLayoutP.substring(1);
        Dropdown layoutModeDropdown = new Dropdown(0, 0, 350, "",
                List.of("Windows", "Dynamic"),
                displayLayoutP,
                v -> {
                    config.layoutMode = v.toLowerCase();
                    ConfigManager.save(config);
                    needsRebuild = true;
                });
        layoutModeDropdown.setFixedWidth(350);
        layoutSectionLast = layoutModeDropdown;
        generalTab.addChild(layoutModeDropdown);

        // Sidebar position (only in dynamic mode)
        if (panelMode) {
            generalTab.addChild(new Label(0, 0, 350, "Sidebar position:", cs.textSecondary()));
            String currentSideP = config.sidebarPosition != null ? config.sidebarPosition : "left";
            String displaySideP = currentSideP.substring(0, 1).toUpperCase() + currentSideP.substring(1);
            Dropdown sidebarPosDropdown = new Dropdown(0, 0, 350, "",
                    List.of("Left", "Right"),
                    displaySideP,
                    v -> {
                        config.sidebarPosition = v.toLowerCase();
                        ConfigManager.save(config);
                        if (panelLayout != null) {
                            panelLayout.setSidebarSide(v.toLowerCase());
                        }
                    });
            sidebarPosDropdown.setFixedWidth(350);
            layoutSectionLast = sidebarPosDropdown;
            generalTab.addChild(sidebarPosDropdown);
        }

        generalTab.addChild(new Label(0, 0, 350, ""));
        generalTab.addChild(new Button(0, 0, 150, "Reset Window Positions", () -> {
            init();
        }));

        // ── Theme Tab ───────────────────────────────────────────────────
        Panel themeTab = tabs.addTab("Theme");
        themeTab.addChild(new Label(0, 0, 350, "Select a theme:", cs.textSecondary()));

        Map<String, ColorScheme> availableThemes = ThemeManager.getInstance().getThemes();
        List<String> themeNames = new ArrayList<>(availableThemes.keySet());

        String currentThemeName = ColorScheme.get().name().toLowerCase(Locale.ROOT);
        Dropdown themeDropdownPanel = new Dropdown(0, 0, 350, "",
                themeNames, currentThemeName,
                v -> {
                    ColorScheme theme = ThemeManager.getInstance().getTheme(v);
                    if (theme != null) {
                        ColorScheme.setActive(theme);
                        config.activeTheme = theme.name();
                        ConfigManager.save(config);
                        needsRebuild = true;
                    }
                });
        themeDropdownPanel.setFixedWidth(350);
        themeTab.addChild(themeDropdownPanel);

        themeTab.addChild(new Label(0, 0, 350, "Current: " + ColorScheme.get().name(), cs.accent()));

        themeTab.addChild(new CheckBox(0, 0, 350, "Enable gradients", config.gradientsEnabled, v -> {
            config.gradientsEnabled = v;
            ColorScheme.setGradientsEnabled(v);
            ConfigManager.save(config);
        }));

        themeTab.addChild(new CheckBox(0, 0, 350, "Background gradient overlay", config.backgroundGradientEnabled, v -> {
            config.backgroundGradientEnabled = v;
            ColorScheme.setBackgroundGradientEnabled(v);
            ConfigManager.save(config);
        }));


        /* ── Theme Creator (disabled for now) ──────────────────────────────────
        themeTab.addChild(new Label(0, 0, 350, ""));
        themeTab.addChild(new Label(0, 0, 350, "\u258C Create Theme", cs.accent()));
        themeCreatorPanel = new ThemeCreatorPanel(0, 0, 350);
        themeTab.addChild(themeCreatorPanel);
        */

        // ── Export Tab ──────────────────────────────────────────────────────
        Panel exportTab = tabs.addTab("Export");
        exportTab.addChild(new Label(0, 0, 350, "Export current data:", cs.textSecondary()));

        exportTab.addChild(new Button(0, 0, 100, "Export JSON", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String json = LogExporter.exportJson(data, eventBus.getEvents());
            try {
                Path path = LogExporter.saveToFile(json, "json");
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
                openExportFolder();
            } catch (IOException e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
            }
        }));
        exportTab.addChild(new Button(0, 0, 100, "Export CSV", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String csv = LogExporter.exportCsv(data, eventBus.getEvents());
            try {
                Path path = LogExporter.saveToFile(csv, "csv");
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
                openExportFolder();
            } catch (IOException e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
            }
        }));
        exportTab.addChild(new Button(0, 0, 100, "Copy to Clipboard", () -> {
            if (session == null) return;
            ServerLogData data = session.toLogData();
            String text = LogExporter.exportClipboard(data, eventBus.getEvents());
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Copied to clipboard"));
        }));

        // ── API Tab ──────────────────────────────────────────────────────
        Panel apiTab = tabs.addTab("API");
        com.archivist.api.ApiGuiHelper.build(apiTab, config, session,
                ArchivistMod.getInstance() != null ? ArchivistMod.getInstance().getApiSyncManager() : null,
                eventBus, () -> {
                    settingsActiveTab = tabs.getActiveTab();
                    if (panelLayout != null) panelLayout.setActiveSection("Settings");
                });

        // ── Rules Tab ───────────────────────────────────────────────────
        Panel rulesTab = tabs.addTab("Rules");
        var ruleDbPanel = ArchivistMod.getInstance() != null ? ArchivistMod.getInstance().getGuiRuleDatabase() : null;
        int ruleCountPanel = ruleDbPanel != null ? ruleDbPanel.getAll().size() : 0;
        rulesTab.addChild(new Label(0, 0, 350, "Rules (" + ruleCountPanel + ")", cs.textSecondary()));

        var ruleDb = ruleDbPanel;
        if (ruleDb != null) {
            var allRules = ruleDb.getAll();
            ScrollableList ruleList = new ScrollableList(0, 0, 350, 140);

            if (allRules.isEmpty()) {
                ruleList.addItem("No rules saved yet.", cs.textSecondary());
                ruleList.addItem("Use X on a container to", cs.textSecondary());
                ruleList.addItem("capture a GUI rule.", cs.textSecondary());
            } else {
                for (var rule : allRules) {
                    String label = rule.pluginName() + " (" + rule.patterns().size() + " pattern"
                            + (rule.patterns().size() != 1 ? "s" : "") + ")";
                    ruleList.addItem(label, cs.textPrimary());
                }

                ruleList.setOnRightClick((item, idx, mx, my) -> {
                    if (idx < 0 || idx >= allRules.size()) return;
                    var rule = allRules.get(idx);
                    ContextMenu menu = new ContextMenu(mx, my);
                    menu.addItem("Delete", () -> {
                        ruleDb.delete(rule.pluginId());
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Deleted rule: " + rule.pluginName()));
                        settingsActiveTab = tabs.getActiveTab();
                        if (panelLayout != null) panelLayout.setActiveSection("Settings");
                    });
                    PopupLayer.open(menu, () -> new int[]{mx, my}, null);
                });
            }

            rulesTab.addChild(ruleList);
            rulesTab.addChild(new Button(0, 0, 150, "Reload Rules", () -> {
                ruleDb.reload();
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Reloaded " + ruleDb.getAll().size() + " rules"));
                settingsActiveTab = tabs.getActiveTab();
                if (panelLayout != null) panelLayout.setActiveSection("Settings");
            }));
        }

        tabs.setActiveTab(settingsActiveTab);
        panel.addChild(tabs);
    }

    private void buildAccountManagerPanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();

        ArchivistMod mod = ArchivistMod.getInstance();
        var acctMgr = mod != null ? mod.getAccountManager() : null;

        int accountCount = acctMgr != null ? acctMgr.getAccounts().size() : 0;
        panel.addChild(new Label(0, 0, 350, "Accounts (" + accountCount + ")", cs.accent()));
        panel.addChild(new Button(0, 0, 100, "Refresh", () -> {
            if (panelLayout != null) panelLayout.setActiveSection("Accounts");
        }));

        int listHeight = Math.max(28, accountCount * 12 + 4);
        ScrollableList accountList = new ScrollableList(0, 0, 350, listHeight);

        if (acctMgr != null) {
            var accounts = acctMgr.getAccounts();
            for (var acct : accounts) {
                boolean isActive = acct.id.equals(acctMgr.getActiveAccountId());
                String prefix = isActive ? "\u25B6 " : "  ";
                int color = isActive ? cs.accent() : cs.textPrimary();
                String display = prefix + acct.username + " [" + acct.type.name().toLowerCase() + "]";
                accountList.addItem(new ScrollableList.ListItem(display, color, acct));
            }

            accountList.setOnSelect(idx -> {
                var items = accountList.getItems();
                if (idx < 0 || idx >= items.size()) return;
                var entry = (com.archivist.account.AccountEntry) items.get(idx).data;
                if (entry == null || entry.id.equals(acctMgr.getActiveAccountId())) return;
                accountStatusMessages.clear();
                acctMgr.switchAccount(entry.id, msg -> {
                    accountStatusMessages.add(msg);
                    Minecraft.getInstance().execute(() -> {
                        if (panelLayout != null) panelLayout.setActiveSection("Accounts");
                    });
                });
                if (panelLayout != null) panelLayout.setActiveSection("Accounts");
            });

            accountList.setOnRightClick((item, index, mx, my) -> {
                var entry = (com.archivist.account.AccountEntry) item.data;
                if (entry == null) return;
                boolean isActive = entry.id.equals(acctMgr.getActiveAccountId());
                if (isActive && entry.isOriginal) return;
                ContextMenu menu = new ContextMenu(mx, my);
                if (!isActive) {
                    menu.addItem("Switch", () -> {
                        accountStatusMessages.clear();
                        acctMgr.switchAccount(entry.id, msg -> {
                            accountStatusMessages.add(msg);
                            Minecraft.getInstance().execute(() -> {
                                if (panelLayout != null) panelLayout.setActiveSection("Accounts");
                            });
                        });
                        if (panelLayout != null) panelLayout.setActiveSection("Accounts");
                    });
                }
                if (!entry.isOriginal) {
                    menu.addItem("Delete", () -> {
                        acctMgr.removeAccount(entry.id);
                        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Removed account"));
                        if (panelLayout != null) panelLayout.setActiveSection("Accounts");
                    });
                }
                PopupLayer.open(menu, () -> new int[]{mx, my}, null);
            });
        }

        panel.addChild(accountList);

        if (acctMgr != null) {
            panel.addChild(new Label(0, 0, 350, ""));
            panel.addChild(new Label(0, 0, 350, "\u258C Add Account", cs.accent()));

            List<String> addTypes = List.of("Offline", "Token");
            Dropdown addTypeDropdown = new Dropdown(0, 0, 350, "",
                    addTypes, accountAddType,
                    v -> {
                        accountAddType = v;
                        if (panelLayout != null) panelLayout.setActiveSection("Accounts");
                    });
            addTypeDropdown.setFixedWidth(350);
            panel.addChild(addTypeDropdown);

            switch (accountAddType) {
                // Microsoft login disabled — needs Azure AD app registration
                case "Offline" -> {
                    TextField crackedName = persistentTextField("acct_offline_name", 0, 0, 350, "Username...");
                    panel.addChild(crackedName);
                    panel.addChild(new Button(0, 0, 150, "Add Offline Account", () -> {
                        String name = crackedName.getText().trim();
                        if (!name.isEmpty()) {
                            acctMgr.addCrackedAccount(name);
                            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Added offline account: " + name));
                            crackedName.clear();
                            clearSavedTextField("acct_offline_name");
                            if (panelLayout != null) panelLayout.setActiveSection("Accounts");
                        }
                    }));
                }
                case "Token" -> {
                    TextField tokenField = persistentTextField("acct_token_value", 0, 0, 350, "Access token...");
                    panel.addChild(tokenField);
                    panel.addChild(new Button(0, 0, 150, "Add Token Account", () -> {
                        String token = tokenField.getText().trim();
                        if (!token.isEmpty()) {
                            acctMgr.addTokenAccount("Token Account", token);
                            eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Added token account"));
                            tokenField.clear(); clearSavedTextField("acct_token_value");
                            if (panelLayout != null) panelLayout.setActiveSection("Accounts");
                        }
                    }));
                }
            }

            if (!accountStatusMessages.isEmpty()) {
                panel.addChild(new Label(0, 0, 350, ""));
                for (String msg : accountStatusMessages) {
                    panel.addChild(new Label(0, 0, 350, msg, cs.textSecondary()));
                }
            }
        }
    }

    private void buildProxyManagerPanel(Panel panel) {
        ColorScheme cs = ColorScheme.get();

        ArchivistMod mod = ArchivistMod.getInstance();
        var proxyMgr = mod != null ? mod.getProxyManager() : null;

        panel.addChild(new Label(0, 0, 350, "\u258C Status", cs.accent()));
        if (proxyMgr != null) {
            boolean enabled = proxyMgr.isProxyEnabled();
            var active = proxyMgr.getActiveProfile();
            String statusText = !enabled ? "Disabled" :
                    active.map(p -> "Active: " + p.name + " (" + p.type + ")").orElse("Enabled (no profile selected)");
            int statusColor = enabled && active.isPresent() ? cs.eventConnect() : cs.textSecondary();
            panel.addChild(new Label(0, 0, 350, "  " + statusText, statusColor));

            panel.addChild(new CheckBox(0, 0, 350, "Enable Proxy",
                    proxyMgr.isProxyEnabled(),
                    v -> {
                        proxyMgr.setProxyEnabled(v);
                        if (panelLayout != null) panelLayout.setActiveSection("Proxy");
                    }));

            panel.addChild(new Label(0, 0, 350, ""));
            panel.addChild(new Label(0, 0, 350, "\u258C Profiles", cs.accent()));

            var profiles = proxyMgr.getProfiles();
            if (profiles.isEmpty()) {
                panel.addChild(new Label(0, 0, 350, "  No profiles configured.", cs.textSecondary()));
            } else {
                for (var profile : profiles) {
                    boolean isActive = profile.id.equals(proxyMgr.getActiveProfileId());
                    String prefix = isActive ? "\u25B6 " : "  ";
                    int color = isActive ? cs.accent() : cs.textPrimary();
                    panel.addChild(new Label(0, 0, 350, prefix + profile.name + " [" + profile.type + "]", color));
                    panel.addChild(new Label(0, 0, 350, "    " + profile.address + ":" + profile.port, cs.textSecondary()));

                    if (!isActive) {
                        final String id = profile.id;
                        panel.addChild(new Button(0, 0, 100, "Select", () -> {
                            proxyMgr.setActiveProfile(id);
                            if (panelLayout != null) panelLayout.setActiveSection("Proxy");
                        }));
                    }
                    final String removeId = profile.id;
                    panel.addChild(new Button(0, 0, 100, "Remove", () -> {
                        proxyMgr.removeProfile(removeId);
                        if (panelLayout != null) panelLayout.setActiveSection("Proxy");
                    }));
                }
            }

            panel.addChild(new Label(0, 0, 350, ""));
            panel.addChild(new Label(0, 0, 350, "\u258C Add Profile", cs.accent()));

            TextField nameField = persistentTextField("proxy_name", 0, 0, 350, "Profile name...");
            TextField addrField = persistentTextField("proxy_addr", 0, 0, 350, "Address (e.g. 127.0.0.1)...");
            TextField portField = persistentTextField("proxy_port", 0, 0, 350, "Port (e.g. 1080)...");
            portField.setOnChange(text -> {
                savedTextFields.put("proxy_port", text);
                try {
                    int port = Integer.parseInt(text);
                    portField.setError(port < 1 || port > 65535);
                    portField.setErrorTooltip(port < 1 || port > 65535 ? "Port must be 1-65535" : null);
                } catch (NumberFormatException e) {
                    portField.setError(!text.isEmpty());
                    portField.setErrorTooltip(text.isEmpty() ? null : "Port must be a number (1-65535)");
                }
            });
            TextField userField = persistentTextField("proxy_user", 0, 0, 350, "Username (optional)...");
            TextField passField = persistentTextField("proxy_pass", 0, 0, 350, "Password (optional)...");

            panel.addChild(nameField);
            panel.addChild(addrField);
            panel.addChild(portField);
            panel.addChild(userField);
            panel.addChild(passField);

            List<String> typeNames = List.of("SOCKS5", "SOCKS4");
            Dropdown typeDropdown = new Dropdown(0, 0, 350, "",
                    typeNames, "SOCKS5", v -> {});
            typeDropdown.setFixedWidth(350);
            panel.addChild(typeDropdown);

            panel.addChild(new Button(0, 0, 150, "Add Profile", () -> {
                String name = nameField.getText().trim();
                String addr = addrField.getText().trim();
                String portStr = portField.getText().trim();
                if (name.isEmpty() || addr.isEmpty() || portStr.isEmpty()) return;

                try {
                    int port = Integer.parseInt(portStr);
                    var type = "SOCKS4".equals(typeDropdown.getSelectedValue())
                            ? com.archivist.proxy.ProxyProfile.ProxyType.SOCKS4
                            : com.archivist.proxy.ProxyProfile.ProxyType.SOCKS5;
                    var profile = new com.archivist.proxy.ProxyProfile(name, type, addr, port);
                    profile.username = userField.getText().trim();
                    String pass = passField.getText().trim();
                    if (!pass.isEmpty()) {
                        profile.passwordEncoded = com.archivist.account.SecureStorage.encrypt(pass);
                    }
                    proxyMgr.addProfile(profile);
                    eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Added proxy: " + name));
                    nameField.clear(); clearSavedTextField("proxy_name");
                    addrField.clear(); clearSavedTextField("proxy_addr");
                    portField.clear(); clearSavedTextField("proxy_port");
                    userField.clear(); clearSavedTextField("proxy_user");
                    passField.clear(); clearSavedTextField("proxy_pass");
                    if (panelLayout != null) panelLayout.setActiveSection("Proxy");
                } catch (NumberFormatException e) {
                    eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Invalid port number"));
                }
            }));
        }
    }

    /**
     * Populates the split-view detail panel with all data for a selected server log.
     *
     * @param detailPanel the right-side Panel to populate
     * @param log         the selected server's log data
     * @param allLogs     the full list of logs (for refresh after delete)
     * @param refreshAction action to refresh the server list (panel or window mode)
     */
    private void populateDetailPanel(Panel detailPanel, ServerLogData log,
                                     List<ServerLogData> allLogs, Runnable refreshAction) {
        detailPanel.clearChildren();
        detailPanel.resetScroll();
        ColorScheme cs = ColorScheme.get();

        // Server address header
        String displayName = getLogDisplayName(log);
        detailPanel.addChild(new Label(0, 0, 300, displayName, cs.accent()));

        // ── Connection section (collapsible) ──
        CollapsibleSection connSection = new CollapsibleSection(0, 0, 300, "Connection");
        connSection.addChild(new Label(0, 0, 300, "IP: " + log.serverInfo().ip(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Port: " + log.serverInfo().port(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Domain: " + log.serverInfo().domain(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Brand: " + log.serverInfo().brand(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Version: " + log.serverInfo().version(), cs.textPrimary()));
        connSection.addChild(new Label(0, 0, 300, "Players: " + log.serverInfo().playerCount(), cs.textPrimary()));
        detailPanel.addChild(connSection);

        // ── Plugins section (collapsible) ──
        CollapsibleSection pluginSection = new CollapsibleSection(0, 0, 300, "Plugins (" + log.plugins().size() + ")");
        for (ServerLogData.PluginEntry p : log.plugins()) {
            pluginSection.addChild(new Label(0, 0, 300, p.name(), cs.eventPlugin()));
        }
        if (log.plugins().isEmpty()) {
            pluginSection.addChild(new Label(0, 0, 300, "(none)", cs.textSecondary()));
        }
        detailPanel.addChild(pluginSection);

        // ── GUI Plugins section (collapsible, only if any exist) ──
        if (!log.guiPlugins().isEmpty()) {
            CollapsibleSection guiPluginSection = new CollapsibleSection(0, 0, 300, "GUI Plugins (" + log.guiPlugins().size() + ")");
            for (ServerLogData.GuiPluginEntry gp : log.guiPlugins()) {
                String conf = String.format("%.0f%%", gp.confidence() * 100);
                guiPluginSection.addChild(new Label(0, 0, 300, gp.pluginName() + " (" + conf + ")", cs.eventPlugin()));
            }
            detailPanel.addChild(guiPluginSection);
        }

        // ── Worlds section (collapsible) ──
        CollapsibleSection worldsSection = new CollapsibleSection(0, 0, 300, "Worlds (" + log.worlds().size() + ")");
        for (ServerLogData.WorldEntry w : log.worlds()) {
            worldsSection.addChild(new Label(0, 0, 300, w.dimension(), cs.textPrimary()));
        }
        if (log.worlds().isEmpty()) {
            worldsSection.addChild(new Label(0, 0, 300, "(none)", cs.textSecondary()));
        }
        detailPanel.addChild(worldsSection);

        // ── Network section (collapsible, only if detected addresses exist) ──
        if (!log.detectedAddresses().isEmpty()) {
            CollapsibleSection networkSection = new CollapsibleSection(0, 0, 300, "Network");
            for (String addr : log.detectedAddresses()) {
                networkSection.addChild(new Label(0, 0, 300, addr, cs.textPrimary()));
            }
            detailPanel.addChild(networkSection);
        }

        // ── Action buttons ──
        detailPanel.addChild(new Label(0, 0, 300, ""));

        Button exportBtn = new Button(0, 0, 100, "Export", () -> {
            String json = LogExporter.exportJson(log, List.of());
            try {
                Path path = LogExporter.saveToFile(json, "json");
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
            } catch (IOException e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
            }
        });
        exportBtn.setTooltip("Export server log as JSON");
        detailPanel.addChild(exportBtn);

        Button connectBtn = new Button(0, 0, 100, "Connect", () -> {
            String connectAddr = !"unknown".equals(log.serverInfo().domain())
                    ? log.serverInfo().domain()
                    : log.serverInfo().ip();
            if (log.serverInfo().port() != 25565) {
                connectAddr += ":" + log.serverInfo().port();
            }
            final String serverAddr = connectAddr;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                try {
                    net.minecraft.client.multiplayer.ServerData serverData =
                            new net.minecraft.client.multiplayer.ServerData(displayName, serverAddr,
                                    net.minecraft.client.multiplayer.ServerData.Type.OTHER);
                    net.minecraft.client.multiplayer.resolver.ServerAddress parsed =
                            net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(serverAddr);
                    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                            mc.screen, mc, parsed, serverData, false, null);
                } catch (Exception e) {
                    eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Quick-connect failed: " + e.getMessage()));
                }
            });
        });
        connectBtn.setTooltip("Quick connect to this server");
        detailPanel.addChild(connectBtn);

        Button deleteBtn = new Button(0, 0, 100, "Delete", () -> {
            try {
                Path logDir = JsonLogger.getLogsDirectory();
                String filename = JsonLogger.getLogFile(log);
                Files.deleteIfExists(logDir.resolve(filename));
                eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Deleted: " + filename));
                refreshAction.run();
            } catch (Exception e) {
                eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Delete failed: " + e.getMessage()));
            }
        });
        deleteBtn.setTooltip("Delete this server log");
        detailPanel.addChild(deleteBtn);

        detailPanel.layoutChildren();
    }

    private void addKVToDetailPanel(Panel panel, String key, String value) {
        panel.addChild(new Label(0, 0, 300, key + ": " + value, ColorScheme.get().textPrimary()));
    }

    private void showServerLogDetail(ServerLogData log) {
        // Populate server info window with historical data
        serverInfoWindow.clearChildren();
        serverInfoWindow.addChild(new Label(0, 0, 180, "\u258C Connection", ColorScheme.get().accent()));
        addKV(serverInfoWindow, "IP", log.serverInfo().ip());
        addKV(serverInfoWindow, "Port", String.valueOf(log.serverInfo().port()));
        addKV(serverInfoWindow, "Domain", log.serverInfo().domain());
        addKV(serverInfoWindow, "Version", log.serverInfo().version());
        addKV(serverInfoWindow, "Brand", log.serverInfo().brand());
        addKV(serverInfoWindow, "Players", String.valueOf(log.serverInfo().playerCount()));
        addKV(serverInfoWindow, "Last Seen", log.timestamp());

        // ── Worlds section ──
        serverInfoWindow.addChild(new Label(0, 0, 180, ""));
        serverInfoWindow.addChild(new Label(0, 0, 180, "\u258C Worlds (" + log.worlds().size() + ")", ColorScheme.get().accent()));
        for (ServerLogData.WorldEntry ws : log.worlds()) {
            serverInfoWindow.addChild(new Label(0, 0, 180, ws.dimension(), ColorScheme.get().accent()));
            serverInfoWindow.addChild(new Label(0, 0, 180, "  " + ws.timestamp(), ColorScheme.get().textSecondary()));
            if (ws.resourcePack() != null) {
                serverInfoWindow.addChild(new Label(0, 0, 180, "  RP: " + ws.resourcePack(), ColorScheme.get().textSecondary()));
            }
        }
        if (!log.detectedAddresses().isEmpty()) {
            serverInfoWindow.addChild(new Label(0, 0, 180, ""));
            serverInfoWindow.addChild(new Label(0, 0, 180, "Detected Addresses:", ColorScheme.get().accent()));
            for (String addr : log.detectedAddresses()) {
                serverInfoWindow.addChild(new Label(0, 0, 180, "  " + addr, ColorScheme.get().textSecondary()));
            }
        }
        serverInfoWindow.setVisible(true);
        viewingServerLog = true;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Tick
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void tick() {
        super.tick();
        if (needsRebuild) {
            needsRebuild = false;
            initialized = false;
            init();
            return;
        }
        // Consume theme creator rebuild flag without triggering full init() —
        // colors already apply live via ColorScheme.setActive()
        // if (themeCreatorPanel != null) themeCreatorPanel.consumeRebuild(); // Theme creator disabled
        ColorScheme.get().tick();
        if (tutorialOverlay != null && tutorialOverlay.isActive()) {
            tutorialOverlay.tick();
        }
        if (archivistWebPrompt != null && archivistWebPrompt.isActive()) {
            archivistWebPrompt.tick();
        }

        // Dynamic mode: tick the panel layout and handle pending removal of detached windows
        if (panelMode && panelLayout != null) {
            panelLayout.tick();

            // Remove any remaining pending-removal windows instantly
            if (!pendingRemoval.isEmpty()) {
                for (DraggableWindow w : pendingRemoval) {
                    String sectionName = w.getTitle();
                    panelLayout.markDocked(sectionName);
                    detachedSectionNames.remove(sectionName);
                    windows.remove(w);
                }
                pendingRemoval.clear();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Render
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        TextSelectionManager.beginFrame();
        TextSelectionManager.updateMouse(mouseX, mouseY);

        // ── Layer 0: Render parent screen underneath ──
        if (parent != null) {
            parent.render(g, -1, -1, delta);
        }

        // ── Screen-level animation update ──────────────────────────────────
        boolean screenAnimating = screenAnimState != ScreenAnimState.NONE;
        float bgAlpha = 1f;
        float fgScale = 1f;
        float fgAlpha = 1f;

        if (screenAnimating) {
            screenAnimProgress += SCREEN_ANIM_SPEED;
            if (screenAnimProgress >= 1f) {
                screenAnimProgress = 1f;
                if (screenAnimState == ScreenAnimState.CLOSING) {
                    screenAnimState = ScreenAnimState.NONE;
                    performActualClose();
                    return;
                }
                screenAnimState = ScreenAnimState.NONE;
                screenAnimating = false;
            }
        }

        if (screenAnimating) {
            if (screenAnimState == ScreenAnimState.OPENING) {
                float t = screenEaseOut(screenAnimProgress);
                // Background appears almost instantly (very fast fade)
                bgAlpha = Math.min(screenAnimProgress * 5f, 1f);
                fgScale = SCREEN_SCALE_START + (1f - SCREEN_SCALE_START) * t;
                fgAlpha = t;
            } else {
                float t = screenEaseIn(screenAnimProgress);
                // Background disappears almost instantly (very fast fade)
                bgAlpha = 1f - Math.min(screenAnimProgress * 5f, 1f);
                fgScale = 1f - (1f - SCREEN_SCALE_START) * t;
                fgAlpha = 1f - t;
            }
        }

        // ── Layer 1: Screen overlay (no scale, just alpha — background layer) ──
        int overlayColor = ColorScheme.get().screenOverlay();
        if (screenAnimating) {
            int baseAlpha = (overlayColor >>> 24) & 0xFF;
            overlayColor = ColorScheme.withAlpha(overlayColor, (int)(baseAlpha * bgAlpha));
        }
        g.fill(0, 0, width, height, overlayColor);

        // ── Layer 2: Gradient overlay (no scale, just alpha — background layer) ──
        GradientConfig grad = ColorScheme.get().getBackgroundGradient();
        if (grad != null && ColorScheme.backgroundGradientEnabled()) {
            int topColor = grad.topColor();
            int bottomColor = grad.bottomColor();
            if (screenAnimating) {
                topColor = ColorScheme.withAlpha(topColor, (int)(((topColor >>> 24) & 0xFF) * bgAlpha));
                bottomColor = ColorScheme.withAlpha(bottomColor, (int)(((bottomColor >>> 24) & 0xFF) * bgAlpha));
            }
            RenderUtils.drawGradientRect(g, 0, 0, this.width, this.height, topColor, bottomColor);
        }

        // ── Foreground scale transform (windows, taskbar, popups) ──
        if (screenAnimating) {
            float centerX = width / 2f;
            float centerY = height / 2f;
            var pose = g.pose();
            //? if >=1.21.9 {
            pose.pushMatrix();
            pose.translate(centerX, centerY);
            pose.scale(fgScale, fgScale);
            pose.translate(-centerX, -centerY);
            //?} else {
            /*pose.pushPose();
            pose.translate(centerX, centerY, 0);
            pose.scale(fgScale, fgScale, 1);
            pose.translate(-centerX, -centerY, 0);*/
            //?}
        }

        // Update live data
        updateLiveData();

        if (reportMode) {
            renderReport(g, mouseX, mouseY);
            CopyToast.render(g);
        } else {
            // ── Window / Dynamic Mode Rendering ──
            // Update active state
            for (DraggableWindow w : windows) w.setActive(false);
            for (int i = windows.size() - 1; i >= 0; i--) {
                if (windows.get(i).isVisible() && !windows.get(i).isAnimating()) {
                    windows.get(i).setActive(true);
                    break;
                }
            }

            // ── Tooltip begin frame ──
            TooltipManager.beginFrame();

            // Taskbar renders below windows — outside screen scale transform
            if (screenAnimating) {
                //? if >=1.21.9
                g.pose().popMatrix();
                //? if <1.21.9
                /*g.pose().popPose();*/
            }
            if (!panelMode) {
                taskbar.updatePosition(width, height);
                taskbar.render(g, mouseX, mouseY, delta);
            }
            if (screenAnimating) {
                float centerX = width / 2f;
                float centerY = height / 2f;
                //? if >=1.21.9 {
                g.pose().pushMatrix();
                g.pose().translate(centerX, centerY);
                g.pose().scale(fgScale, fgScale);
                g.pose().translate(-centerX, -centerY);
                //?} else {
                /*g.pose().pushPose();
                g.pose().translate(centerX, centerY, 0);
                g.pose().scale(fgScale, fgScale, 1);
                g.pose().translate(-centerX, -centerY, 0);*/
                //?}
            }

            // Render windows (first = back, last = front) — above the taskbar
            for (DraggableWindow w : windows) {
                w.render(g, mouseX, mouseY, delta);
            }

            // Popup overlay (above windows and taskbar)
            PopupLayer.render(g, mouseX, mouseY, delta);

            // Global search overlay (above everything except tooltips)
            if (globalSearch.isOpen()) {
                globalSearch.render(g, mouseX, mouseY, delta);
            }

            // ── Copy toast notification ──
            CopyToast.render(g);

            // ── Tutorial overlay (above everything except tooltips) ──
            if (tutorialOverlay != null && tutorialOverlay.isActive()) {
                tutorialOverlay.render(g, mouseX, mouseY, delta);
            }

            // ── Archivist-web prompt overlay (after tutorial) ──
            if (archivistWebPrompt != null && archivistWebPrompt.isActive()) {
                archivistWebPrompt.render(g, mouseX, mouseY, delta);
            }

            // ── Tooltips (topmost) ──
            TooltipManager.render(g);
        }

        // ── Screen animation cleanup ──
        if (screenAnimating) {
            //? if >=1.21.9
            g.pose().popMatrix();
            //? if <1.21.9
            /*g.pose().popPose();*/
        }

        // ── Finalize programmatic text selection (CTRL+A) ──
        TextSelectionManager.finalizeSelection();

        // ── Cursor update ──
        updateCursor(mouseX, mouseY);

        // ── Real-time window state saving (throttled ~1/sec) ──
        if (++saveThrottleCounter >= 60) {
            saveThrottleCounter = 0;
            saveWindowStates();
        }
    }

    private void updateCursor(int mouseX, int mouseY) {
        if (!cursorsInitialized) {
            cursorsInitialized = true;
            cursorArrow = GLFW.glfwCreateStandardCursor(GLFW.GLFW_ARROW_CURSOR);
            cursorHand = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
            cursorHResize = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
            cursorVResize = GLFW.glfwCreateStandardCursor(GLFW.GLFW_VRESIZE_CURSOR);
            // GLFW 3.4 cursors — raw constants, 0 if unsupported
            cursorNWSE = safeCreateCursor(0x00036007);
            cursorNESW = safeCreateCursor(0x00036008);
            cursorMove = safeCreateCursor(0x00036009);
        }

        DraggableWindow.CursorType type = DraggableWindow.CursorType.ARROW;
        // Check windows front-to-back; active drag/resize wins regardless of mouse position
        for (int i = windows.size() - 1; i >= 0; i--) {
            DraggableWindow w = windows.get(i);
            if (!w.isVisible()) continue;
            DraggableWindow.CursorType wt = w.getCursorType(mouseX, mouseY);
            if (wt != DraggableWindow.CursorType.ARROW) {
                type = wt;
                break;
            }
            if (w.containsPointExact(mouseX, mouseY)) break; // window blocks those behind
        }

        if (type != lastCursorType) {
            lastCursorType = type;
            long windowHandle = Minecraft.getInstance().getWindow()./*? if >=1.21.9 {*/handle()/*?} else {*//*getWindow()*//*?}*/;
            long cursor = switch (type) {
                case HAND -> cursorHand;
                case H_RESIZE -> cursorHResize;
                case V_RESIZE -> cursorVResize;
                case NWSE_RESIZE -> cursorNWSE != 0 ? cursorNWSE : cursorHResize;
                case NESW_RESIZE -> cursorNESW != 0 ? cursorNESW : cursorHResize;
                case MOVE -> cursorMove != 0 ? cursorMove : cursorHand;
                default -> cursorArrow;
            };
            GLFW.glfwSetCursor(windowHandle, cursor);
        }
    }

    private void destroyCursors() {
        if (cursorArrow != 0) { GLFW.glfwDestroyCursor(cursorArrow); cursorArrow = 0; }
        if (cursorHand != 0) { GLFW.glfwDestroyCursor(cursorHand); cursorHand = 0; }
        if (cursorHResize != 0) { GLFW.glfwDestroyCursor(cursorHResize); cursorHResize = 0; }
        if (cursorVResize != 0) { GLFW.glfwDestroyCursor(cursorVResize); cursorVResize = 0; }
        if (cursorNWSE != 0) { GLFW.glfwDestroyCursor(cursorNWSE); cursorNWSE = 0; }
        if (cursorNESW != 0) { GLFW.glfwDestroyCursor(cursorNESW); cursorNESW = 0; }
        if (cursorMove != 0) { GLFW.glfwDestroyCursor(cursorMove); cursorMove = 0; }
        cursorsInitialized = false;
    }

    private static long safeCreateCursor(int shape) {
        try {
            return GLFW.glfwCreateStandardCursor(shape);
        } catch (Exception e) {
            return 0;
        }
    }

    private void updateLiveData() {
        // Push new events to console
        List<LogEvent> events = eventBus.getEvents();
        int currentSize = events.size();
        if (currentSize < lastEventCount) {
            // Events were cleared (e.g. by clear command) — reset console output
            if (consoleOutput != null) {
                consoleOutput.clearItems();
            }
            lastEventCount = 0;
        }
        if (currentSize > lastEventCount) {
            for (int i = lastEventCount; i < currentSize; i++) {
                LogEvent event = events.get(i);
                int color = ColorScheme.get().eventColor(event.getType());
                if (consoleOutput != null) {
                    consoleOutput.addItem(event.toString(), color);
                }
            }
            lastEventCount = currentSize;
        }

        // Live-refresh Server & Plugins when new detections arrive
        if (session != null) {
            int currentPluginCount = session.getPlugins().size();
            if (currentPluginCount != lastKnownPluginCount) {
                lastKnownPluginCount = currentPluginCount;
                if (panelMode) {
                    if (panelLayout != null && "Server & Plugins".equals(panelLayout.getActiveSection())) {
                        panelLayout.setActiveSection("Server & Plugins");
                    }
                } else {
                    long now = System.currentTimeMillis();
                    if (now - lastServerInfoRebuild >= 500) {
                        lastServerInfoRebuild = now;
                        buildServerInfoWindow();
                        serverInfoWindow.reflowChildren();
                    }
                }
            }
        }

        // Automation: live-update status labels and log
        updateAutomationLiveData();
    }

    private void updateAutomationLiveData() {
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod == null || mod.getSequenceHandler() == null) return;
        var orch = mod.getSequenceHandler();
        var autoState = orch.getState();
        ColorScheme cs = ColorScheme.get();

        // Update status labels
        if (automationStatusLabel != null) {
            var phase = orch.getPhase();
            int phaseColor = switch (phase) {
                case IDLE, COMPLETED -> cs.textSecondary();
                case CONNECTING, SCANNING, DELAY -> cs.accent();
                case DISCONNECTING -> cs.eventDisconnect();
                case ERROR -> cs.eventError();
            };
            automationStatusLabel.setText("  Status: " + phase.name());
            automationStatusLabel.setColor(phaseColor);
        }
        if (automationTaskLabel != null && orch.getEngine() != null) {
            if (isRunning(orch.getPhase())) {
                automationTaskLabel.setText("  Task: " + orch.getEngine().getCurrentTaskDescription());
            } else {
                automationTaskLabel.setText("");
            }
        }
        if (automationCurrentLabel != null) {
            automationCurrentLabel.setText("  Current: " + orch.getCurrentServerDisplay());
        }
        if (automationProgressLabel != null) {
            java.util.List<String> serverList = orch.getCurrentServerList();
            int total = serverList.isEmpty()
                    ? com.archivist.api.automation.AddressListManager.loadList(
                        orch.getConfig().activeServerList).size()
                    : serverList.size();
            automationProgressLabel.setText("  Completed: " + autoState.getCompletedCount() + "/" + total);
        }
        if (automationFailedLabel != null) {
            java.util.List<String> serverList = orch.getCurrentServerList();
            int total = serverList.isEmpty()
                    ? com.archivist.api.automation.AddressListManager.loadList(
                        orch.getConfig().activeServerList).size()
                    : serverList.size();
            automationFailedLabel.setText("  Failed: " + autoState.getFailedCount()
                    + "  |  Remaining: " + autoState.getRemainingCount(total));
        }
        if (automationElapsedLabel != null) {
            long elapsed = orch.getElapsedMs();
            String elapsedStr = elapsed > 0 ? formatDuration(elapsed) : "\u2014";
            automationElapsedLabel.setText("  Elapsed: " + elapsedStr);
        }

        // Automation logs now go to main Console via EventBus — no separate log tab
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Input
    // ══════════════════════════════════════════════════════════════════════════

    //? if >=1.21.9 {
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        return handleMouseClicked(mouseX, mouseY, button) || super.mouseClicked(event, bl);
    }
    //?} else {
    /*@Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleMouseClicked(mouseX, mouseY, button) || super.mouseClicked(mouseX, mouseY, button);
    }*/
    //?}

    private boolean handleMouseClicked(double mouseX, double mouseY, int button) {
        if (isScreenAnimating()) return false;
        if (tutorialOverlay != null && tutorialOverlay.isActive()) {
            return tutorialOverlay.onMouseClicked(mouseX, mouseY, button);
        }
        if (archivistWebPrompt != null && archivistWebPrompt.isActive()) {
            return archivistWebPrompt.onMouseClicked(mouseX, mouseY, button);
        }
        if (reportMode) return handleReportClick(mouseX, mouseY, button);

        // Unfocus all text fields before processing click; the clicked field will re-focus itself
        unfocusAllTextFields();

        TextSelectionManager.onMousePressed(mouseX, mouseY, button);

        if (PopupLayer.mouseClicked(mouseX, mouseY, button)) return true;

        if (globalSearch.isOpen()) {
            if (globalSearch.containsPoint(mouseX, mouseY)) {
                return globalSearch.onMouseClicked(mouseX, mouseY, button);
            } else {
                globalSearch.close();
            }
        }

        // Windows get input priority (rendered above taskbar)
        for (int i = windows.size() - 1; i >= 0; i--) {
            DraggableWindow w = windows.get(i);
            if (w.isVisible() && w.containsPoint(mouseX, mouseY)) {
                if (w.onMouseClicked(mouseX, mouseY, button)) {
                    bringToFront(w);
                    taskbar.setActiveWindow(w);
                    return true;
                }
            }
        }

        if (!panelMode && taskbar.containsPoint(mouseX, mouseY)) {
            if (taskbar.onMouseClicked(mouseX, mouseY, button)) {
                DraggableWindow active = taskbar.getActiveWindow();
                if (active != null) {
                    bringToFront(active);
                }
                return true;
            }
        }

        return false;
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        double mouseX = event.x(); double mouseY = event.y(); int button = event.button();
        boolean result = handleMouseReleased(mouseX, mouseY, button);
        return result || super.mouseReleased(event);
    }
    //?} else {
    /*@Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean result = handleMouseReleased(mouseX, mouseY, button);
        return result || super.mouseReleased(mouseX, mouseY, button);
    }*/
    //?}

    private boolean handleMouseReleased(double mouseX, double mouseY, int button) {
        if (isScreenAnimating()) return false;
        TextSelectionManager.onMouseReleased();
        if (PopupLayer.mouseReleased(mouseX, mouseY, button)) return true;
        if (panelMode && panelLayout != null) {
            for (int i = windows.size() - 1; i >= 0; i--) {
                DraggableWindow w = windows.get(i);
                boolean wasDragging = w.isDragging();
                if (w.onMouseReleased(mouseX, mouseY, button)) {
                    if (wasDragging) {
                        DraggableWindow mainPanel = windows.isEmpty() ? null : windows.get(0);
                        int panelX = mainPanel != null ? mainPanel.getX() : 0;
                        String side = panelLayout.getSidebarSide();
                        int sidebarEdge = "right".equals(side)
                                ? panelX + (mainPanel != null ? mainPanel.getWidth() : width) - panelLayout.getSidebarWidth()
                                : panelX + panelLayout.getSidebarWidth();
                        boolean overSidebar = "right".equals(side)
                                ? mouseX >= sidebarEdge
                                : mouseX <= sidebarEdge;
                        if (overSidebar) {
                            String windowId = w.getId();
                            if (windowId != null && windowId.startsWith("dynamic_")) {
                                String section = w.getTitle();
                                windows.remove(w);
                                panelLayout.markDocked(section);
                                detachedSectionNames.remove(section);
                                return true;
                            }
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).onMouseReleased(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    //? if >=1.21.9 {
    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double deltaX, double deltaY) {
        if (isScreenAnimating()) return false;
        double mouseX = event.x(); double mouseY = event.y(); int button = event.button();
        return handleMouseDragged(mouseX, mouseY, button, deltaX, deltaY) || super.mouseDragged(event, deltaX, deltaY);
    }
    //?} else {
    /*@Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (isScreenAnimating()) return false;
        return handleMouseDragged(mouseX, mouseY, button, deltaX, deltaY) || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }*/
    //?}

    private boolean handleMouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        TextSelectionManager.onMouseDragged(mouseX, mouseY);
        if (PopupLayer.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).onMouseDragged(mouseX, mouseY, button, deltaX, deltaY)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isScreenAnimating()) return false;
        if (reportMode) {
            reportScroll -= (float) verticalAmount * 10;
            reportScroll = Math.max(0, Math.min(reportScroll, reportMaxScroll));
            return true;
        }
        if (PopupLayer.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)) return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    //? if >=1.21.9 {
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key(); int scanCode = event.scancode(); int modifiers = event.modifiers();
        if (handleKeyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        char chr = (char) event.codepoint(); int modifiers = event.modifiers();
        if (handleCharTyped(chr, modifiers)) return true;
        return super.charTyped(event);
    }
    //?} else {
    /*@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (handleKeyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (handleCharTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }*/
    //?}

    // ══════════════════════════════════════════════════════════════════════════
    // Unified Input Handlers
    // ══════════════════════════════════════════════════════════════════════════

    private boolean handleKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (isScreenAnimating()) {
            // Allow Escape to trigger close during opening animation
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && screenAnimState == ScreenAnimState.OPENING) {
                onClose();
                return true;
            }
            return false;
        }

        // Tutorial overlay intercepts all input while active
        if (tutorialOverlay != null && tutorialOverlay.isActive()) {
            return tutorialOverlay.onKeyPressed(keyCode);
        }
        if (archivistWebPrompt != null && archivistWebPrompt.isActive()) {
            return archivistWebPrompt.onKeyPressed(keyCode);
        }

        // Popup layer intercepts first (Escape closes popup)
        if (PopupLayer.keyPressed(keyCode, scanCode, modifiers)) return true;

        // Global search intercepts first when open
        if (globalSearch.isOpen()) {
            if (globalSearch.onKeyPressed(keyCode, scanCode, modifiers)) return true;
        }

        // Console Enter
        if (keyCode == GLFW.GLFW_KEY_ENTER && consoleInput != null && consoleInput.isFocused()) {
            submitConsoleCommand();
            return true;
        }

        // Escape: in report mode, go back; otherwise close screen
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (reportMode) { reportMode = false; return true; }
            onClose();
            return true;
        }

        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        // Ctrl+Shift+D: panic disconnect all API endpoints
        if (ctrl && shift && keyCode == GLFW.GLFW_KEY_D) {
            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod != null && mod.getApiSyncManager() != null) {
                mod.getApiSyncManager().panicDisconnect();
                ConfigManager.save(config);
                CopyToast.show("All API connections disabled");
                needsRebuild = true;
            }
            return true;
        }

        if (ctrl) {
            if ((panelMode) && panelLayout != null) {
                switch (keyCode) {
                    case GLFW.GLFW_KEY_1 -> { panelLayout.setActiveSection("Server & Plugins"); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_3 -> { panelLayout.setActiveSection("Console"); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_4 -> { panelLayout.setActiveSection("Automation"); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_F -> { openGlobalSearch(); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_S -> { saveConfig(); shortcutConsumedThisFrame = true; return true; }
                }
            } else {
                switch (keyCode) {
                    case GLFW.GLFW_KEY_1 -> { toggleAndFocus(serverInfoWindow); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_2 -> { toggleAndFocus(consoleWindow); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_4 -> { toggleAndFocus(manualLogWindow); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_F -> { openGlobalSearch(); shortcutConsumedThisFrame = true; return true; }
                    case GLFW.GLFW_KEY_S -> { saveConfig(); shortcutConsumedThisFrame = true; return true; }
                }
            }
        }

        // ── Section-level keyboard shortcuts ─────────────────────────────

        // Ctrl+R: Refresh active section
        if (ctrl && !shift && keyCode == GLFW.GLFW_KEY_R) {
            if ((panelMode) && panelLayout != null) {
                String active = panelLayout.getActiveSection();
                if ("Server & Plugins".equals(active)) { if (refreshPlugins != null) refreshPlugins.run(); shortcutConsumedThisFrame = true; return true; }
                if ("Logs".equals(active)) { if (refreshLogs != null) refreshLogs.run(); else panelLayout.setActiveSection("Logs"); shortcutConsumedThisFrame = true; return true; }
                if ("Accounts".equals(active)) { if (refreshAccounts != null) refreshAccounts.run(); else panelLayout.setActiveSection("Accounts"); shortcutConsumedThisFrame = true; return true; }
                if ("Inspector".equals(active)) { if (refreshInspector != null) refreshInspector.run(); else panelLayout.setActiveSection("Inspector"); shortcutConsumedThisFrame = true; return true; }
            } else {
                // Window mode: refresh focused window
                for (int i = windows.size() - 1; i >= 0; i--) {
                    DraggableWindow w = windows.get(i);
                    if (!w.isVisible() || !w.isActive()) continue;
                    if (w == serverInfoWindow && refreshPlugins != null) { refreshPlugins.run(); shortcutConsumedThisFrame = true; return true; }
                    if (w == serverListWindow && refreshLogs != null) { refreshLogs.run(); shortcutConsumedThisFrame = true; return true; }
                    if (w == accountManagerWindow && refreshAccounts != null) { refreshAccounts.run(); shortcutConsumedThisFrame = true; return true; }
                    if (w == inspectorWindow && refreshInspector != null) { refreshInspector.run(); shortcutConsumedThisFrame = true; return true; }
                    break;
                }
            }
        }

        // Delete: delete selected item in Rules or Accounts
        if (keyCode == GLFW.GLFW_KEY_DELETE) {
            if ((panelMode) && panelLayout != null) {
                String active = panelLayout.getActiveSection();
                if ("Settings".equals(active) && deleteSelectedRule != null) {
                    deleteSelectedRule.run();
                    shortcutConsumedThisFrame = true;
                    return true;
                }
                if ("Accounts".equals(active) && deleteSelectedAccount != null) {
                    deleteSelectedAccount.run();
                    shortcutConsumedThisFrame = true;
                    return true;
                }
            } else {
                if (settingsWindow != null && settingsWindow.isVisible() && settingsWindow.isActive() && deleteSelectedRule != null) {
                    deleteSelectedRule.run();
                    shortcutConsumedThisFrame = true;
                    return true;
                }
                if (accountManagerWindow != null && accountManagerWindow.isVisible() && accountManagerWindow.isActive() && deleteSelectedAccount != null) {
                    deleteSelectedAccount.run();
                    shortcutConsumedThisFrame = true;
                    return true;
                }
            }
        }

        // Screen-level Ctrl+C: active text selection takes priority
        if (ctrl && keyCode == GLFW.GLFW_KEY_C && TextSelectionManager.hasActiveSelection()) {
            String selected = TextSelectionManager.getSelectedText();
            if (selected != null && !selected.isEmpty()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(selected);
                CopyToast.show(selected);
                return true;
            }
        }

        // Delegate to windows first (TextField handles its own Ctrl+A/C/V)
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).onKeyPressed(keyCode, scanCode, modifiers)) return true;
        }
        // Dynamic mode: also delegate to the full-screen panel layout
        if (panelMode && panelLayout != null) {
            if (panelLayout.onKeyPressed(keyCode, scanCode, modifiers)) return true;
        }

        // Screen-level Ctrl+A fallback: highlight text line under cursor
        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            for (int i = windows.size() - 1; i >= 0; i--) {
                DraggableWindow w = windows.get(i);
                if (!w.isVisible() || w.isMinimized()) continue;
                String text = w.getTextAtPoint(lastMouseX, lastMouseY);
                if (text != null && !text.isEmpty()) {
                    TextSelectionManager.selectAll(
                            w.getX(), lastMouseY - 1,
                            w.getWidth(), 2);
                    return true;
                }
                if (w.containsPointExact(lastMouseX, lastMouseY)) break;
            }
        }

        // Screen-level Ctrl+C fallback: full text under cursor
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
            for (int i = windows.size() - 1; i >= 0; i--) {
                String text = windows.get(i).getTextAtPoint(lastMouseX, lastMouseY);
                if (text != null && !text.isEmpty()) {
                    Minecraft.getInstance().keyboardHandler.setClipboard(text);
                    CopyToast.show(text);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean handleCharTyped(char chr, int modifiers) {
        if (isScreenAnimating()) return false;
        if (PopupLayer.charTyped(chr, modifiers)) return true;

        if (shortcutConsumedThisFrame) {
            shortcutConsumedThisFrame = false;
            return true;
        }
        if (globalSearch.isOpen()) {
            if (globalSearch.onCharTyped(chr, modifiers)) return true;
        }
        for (int i = windows.size() - 1; i >= 0; i--) {
            if (windows.get(i).onCharTyped(chr, modifiers)) return true;
        }
        // Dynamic mode: also delegate to the full-screen panel layout
        if (panelMode && panelLayout != null) {
            if (panelLayout.onCharTyped(chr, modifiers)) return true;
        }
        return false;
    }

    private void toggleAndFocus(DraggableWindow window) {
        if (window == null) return;
        if (!window.isVisible()) {
            window.setVisible(true);
        }
        window.setMinimized(false);
        bringToFront(window);
    }

    private void openGlobalSearch() {
        if (globalSearch.isOpen()) {
            globalSearch.close();
        } else {
            globalSearch.open();
        }
    }

    private void saveConfig() {
        ConfigManager.save(config);
        eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Config saved"));
    }

    private List<GlobalSearchOverlay.SearchResult> performGlobalSearch(GlobalSearchOverlay.SearchQuery sq) {
        List<GlobalSearchOverlay.SearchResult> results = new ArrayList<>();
        ColorScheme cs = ColorScheme.get();
        String query = sq.query();
        GlobalSearchOverlay.SearchFilter filter = sq.filter();

        // Search console
        if (filter == GlobalSearchOverlay.SearchFilter.ALL && consoleOutput != null) {
            for (ScrollableList.ListItem item : consoleOutput.getItems()) {
                if (item.text.toLowerCase().contains(query)) {
                    results.add(new GlobalSearchOverlay.SearchResult("console", "Console", item.text, item.color));
                }
            }
        }

        // Search server info for brand/version
        if (session != null) {
            if ((filter == GlobalSearchOverlay.SearchFilter.ALL || filter == GlobalSearchOverlay.SearchFilter.BRAND)
                    && session.getBrand() != null && session.getBrand().toLowerCase().contains(query)) {
                results.add(new GlobalSearchOverlay.SearchResult("server_info", "Brand", session.getBrand(), cs.eventBrand()));
            }
            if ((filter == GlobalSearchOverlay.SearchFilter.ALL || filter == GlobalSearchOverlay.SearchFilter.VERSION)
                    && session.getVersion() != null && session.getVersion().toLowerCase().contains(query)) {
                results.add(new GlobalSearchOverlay.SearchResult("server_info", "Version", session.getVersion(), cs.textPrimary()));
            }
        }

        // Cross-server plugin search from stored logs
        if (filter == GlobalSearchOverlay.SearchFilter.ALL || filter == GlobalSearchOverlay.SearchFilter.PLUGINS) {
            try {
                List<ServerLogData> logs = JsonLogger.readAllLogs();
                for (ServerLogData log : logs) {
                    for (ServerLogData.PluginEntry plugin : log.plugins()) {
                        if (plugin.name().toLowerCase().contains(query)) {
                            String serverName = getLogDisplayName(log);
                            results.add(new GlobalSearchOverlay.SearchResult(
                                    "server_list", serverName, plugin.name(), 0xFFFFAA00, true));
                        }
                    }
                    if ((filter == GlobalSearchOverlay.SearchFilter.ALL || filter == GlobalSearchOverlay.SearchFilter.BRAND)
                            && log.serverInfo().brand() != null && log.serverInfo().brand().toLowerCase().contains(query)) {
                        results.add(new GlobalSearchOverlay.SearchResult(
                                "server_list", getLogDisplayName(log), "Brand: " + log.serverInfo().brand(), 0xFFFFAA00, true));
                    }
                }
            } catch (Exception ignored) {}
        }

        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private boolean hasSavedState(String id) {
        return savedWindowStates != null && savedWindowStates.containsKey(id);
    }

    /** Recursively search a widget tree for a TabContainer and save its active tab. */
    private void saveActiveTab(Widget root, java.util.function.IntConsumer setter) {
        if (root == null) return;
        if (root instanceof TabContainer tc) {
            setter.accept(tc.getActiveTab());
            return;
        }
        if (root instanceof DraggableWindow dw) {
            for (Widget child : dw.getChildren()) {
                saveActiveTab(child, setter);
            }
        } else if (root instanceof Panel p) {
            for (Widget child : p.getChildren()) {
                saveActiveTab(child, setter);
            }
        }
    }

    // ── Section entry record for dynamic mode detach ─────────────────────
    private record SectionEntry(String name, Consumer<Panel> contentBuilder) {}

    /**
     * Find a registered section by name for dynamic mode detach/restore.
     */
    private SectionEntry findSection(String name) {
        // Map section names to their content builders
        return switch (name) {
            case "Server & Plugins" -> new SectionEntry(name, this::buildServerInfoPanel);
            case "Console" -> new SectionEntry(name, this::buildConsolePanel);
            case "Automation" -> new SectionEntry(name, this::buildAutomationPanel);
            case "Logs" -> new SectionEntry(name, this::buildServerListPanel);
            case "Inspector" -> new SectionEntry(name, this::buildInspectorPanel);
            case "Manual Log" -> new SectionEntry(name, this::buildManualLogPanel);
            case "Settings" -> new SectionEntry(name, this::buildSettingsPanel);
            case "Accounts" -> new SectionEntry(name, this::buildAccountManagerPanel);
            case "Proxy" -> new SectionEntry(name, this::buildProxyManagerPanel);
            default -> null;
        };
    }

    private TextField persistentTextField(String id, int x, int y, int w, String placeholder) {
        TextField tf = new TextField(x, y, w, placeholder);
        String saved = savedTextFields.get(id);
        if (saved != null && !saved.isEmpty()) tf.setText(saved);
        if (id.equals(savedFocusedField)) {
            tf.setFocused(true);
            if (savedCursorPos >= 0) {
                tf.setCursorPos(savedCursorPos);
                savedCursorPos = -1;
            }
            savedFocusedField = null;
        }
        tf.setOnChange(text -> savedTextFields.put(id, text));
        return tf;
    }

    private void clearSavedTextField(String id) {
        savedTextFields.remove(id);
    }

    /** Unfocus all TextField widgets across all windows and panels. */
    private void unfocusAllTextFields() {
        for (DraggableWindow w : windows) {
            unfocusTextFieldsIn(w.getChildren());
        }
        if (panelLayout != null) {
            Panel cp = panelLayout.getContentPanel();
            if (cp != null) unfocusTextFieldsIn(cp.getChildren());
        }
    }

    private void unfocusTextFieldsIn(java.util.List<Widget> widgets) {
        for (Widget widget : widgets) {
            if (widget instanceof TextField) {
                widget.setFocused(false);
            }
            if (widget instanceof Panel p) {
                unfocusTextFieldsIn(p.getChildren());
            }
            if (widget instanceof DraggableWindow dw) {
                unfocusTextFieldsIn(dw.getChildren());
            }
            if (widget instanceof TabContainer tc) {
                for (int i = 0; i < tc.getTabCount(); i++) {
                    Panel tp = tc.getTabPanel(i);
                    if (tp != null) unfocusTextFieldsIn(tp.getChildren());
                }
            }
        }
    }

    private void bringToFront(DraggableWindow window) {
        // In panel mode, never move the main panel window to the front — it stays at index 0
        if (panelMode && "archivist_panel".equals(window.getId())) return;
        windows.remove(window);
        windows.add(window);
    }

    private void addKV(DraggableWindow window, String key, String value) {
        window.addChild(new Label(0, 0, 180, key + ": " + value, ColorScheme.get().textPrimary()));
    }

    private String getLogDisplayName(ServerLogData log) {
        String domain = log.serverInfo().domain();
        if (domain != null && !domain.isEmpty() && !"unknown".equals(domain)) {
            return domain;
        }
        return log.serverInfo().ip() + ":" + log.serverInfo().port();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Report View
    // ══════════════════════════════════════════════════════════════════════════

    private void renderReport(GuiGraphics g, int mouseX, int mouseY) {
        ColorScheme cs = ColorScheme.get();
        int m = REPORT_MARGIN;
        int contentW = width - m * 2;
        int lineH = REPORT_LINE_H;
        int halfW = contentW / 2;

        // Clip content area
        RenderUtils.enableScissor(g, m, 0, contentW, height);

        int cy = m - (int) reportScroll;

        // ── Title ──
        String title = "SERVER REPORT";
        int titleW = RenderUtils.scaledTextWidth(title);
        RenderUtils.drawText(g, title, (width - titleW) / 2, cy, cs.accent());
        // Back button
        RenderUtils.drawText(g, "< Back", m, cy, cs.textSecondary());
        cy += lineH + 6;

        // ── Connection ──
        cy = drawSectionHeader(g, cs, "Connection", m, cy, contentW);
        String ip = session != null ? session.getIp() : "N/A";
        String port = session != null ? String.valueOf(session.getPort()) : "N/A";
        String domain = session != null ? session.getDomain() : "N/A";
        String brand = session != null ? session.getBrand() : "N/A";
        String version = session != null ? session.getVersion() : "N/A";
        String software = session != null ? session.getServerSoftware() : "";
        String players = session != null ? String.valueOf(session.getPlayerCount()) : "N/A";

        cy = drawKVPair(g, cs, m, cy, halfW, "IP", ip, "Port", port);
        cy = drawKVPair(g, cs, m, cy, halfW, "Domain", domain, "Version", version);
        cy = drawKVPair(g, cs, m, cy, halfW, "Brand", brand, "Players", players);
        if (!software.isEmpty()) {
            cy = drawKVSingle(g, cs, m, cy, "Software", software);
        }

        // MOTD
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCurrentServer() != null && mc.getCurrentServer().motd != null) {
            String motd = mc.getCurrentServer().motd.getString();
            if (!motd.isEmpty()) {
                cy = drawKVSingle(g, cs, m, cy, "MOTD", motd);
            }
        }

        // Resource pack
        if (session != null && session.getResourcePack() != null) {
            cy = drawKVSingle(g, cs, m, cy, "Resource Pack", session.getResourcePack());
        }
        cy += 4;

        // ── IP Info ──
        cy = drawSectionHeader(g, cs, "IP Info", m, cy, contentW);
        IpInfoLookup.IpInfoResult result = ipInfoResult;
        if (result != null && result.isSuccess()) {
            String[] lines = result.ipInfoJson().split("\n");
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    cy = drawKVSingle(g, cs, m, cy, null, line.trim());
                }
            }
        } else if (result != null && result.error() != null) {
            cy = drawKVSingle(g, cs, m, cy, "Status", result.error());
        } else {
            cy = drawKVSingle(g, cs, m, cy, "Status", "Looking up...");
        }
        cy += 4;

        // ── World ──
        cy = drawSectionHeader(g, cs, "World", m, cy, contentW);
        String dim = session != null ? session.getDimension() : "N/A";
        String difficulty = mc.level != null ? mc.level.getDifficulty().name() : "N/A";
        String gamemode = mc.gameMode != null ? mc.gameMode.getPlayerMode().name() : "N/A";
        String dayTime = mc.level != null ? String.valueOf(mc.level.getDayTime() % 24000) : "N/A";
        String border = mc.level != null ? String.format("%.0f", mc.level.getWorldBorder().getSize()) : "N/A";
        String raining = mc.level != null ? String.valueOf(mc.level.isRaining()) : "N/A";

        cy = drawKVPair(g, cs, m, cy, halfW, "Dimension", dim, "Difficulty", difficulty);
        cy = drawKVPair(g, cs, m, cy, halfW, "Gamemode", gamemode, "Day Time", dayTime);
        cy = drawKVPair(g, cs, m, cy, halfW, "Border", border, "Raining", raining);
        cy += 4;

        // ── Session Confidence ──
        cy = drawSectionHeader(g, cs, "Session Confidence", m, cy, contentW);
        SessionConfidence sc = pipeline.getSessionConfidence();
        double conf = sc.getConfidence();
        String confLabel = conf > 0.7 ? "REAL SERVER" : conf > 0.4 ? "UNCERTAIN" : "LOBBY LIKELY";

        // Confidence bar
        int barW = Math.min(contentW - 100, 200);
        int barH = 8;
        int barX = m;
        int barColor = conf > 0.7 ? 0xFF44BB44 : conf > 0.4 ? 0xFFBBBB44 : 0xFFBB4444;
        RenderUtils.drawRect(g, barX, cy, barW, barH, cs.windowBackground());
        RenderUtils.drawRect(g, barX, cy, (int)(barW * conf), barH, barColor);
        RenderUtils.drawText(g, String.format("%.0f%%  %s", conf * 100, confLabel),
                barX + barW + 6, cy - 1, barColor);
        cy += barH + 4;

        // Proxy / Transfer status
        String proxyStr = sc.isProxyDetected() ? "Yes" : "No";
        String transferStr = sc.hasTransferred() ? "Yes" : "No";
        cy = drawKVPair(g, cs, m, cy, halfW, "Proxy Detected", proxyStr, "Transferred", transferStr);
        cy += 2;

        // Signal breakdown
        List<SessionConfidence.Signal> serverSignals = sc.getServerSignals();
        List<SessionConfidence.Signal> lobbySignals = sc.getLobbySignals();

        if (!serverSignals.isEmpty()) {
            RenderUtils.drawText(g, "Server signals:", m, cy, 0xFF44BB44);
            cy += lineH;
            for (SessionConfidence.Signal s : serverSignals) {
                RenderUtils.drawText(g, String.format("  +%.2f  %s", s.weight(), s.reason()), m, cy, 0xFF33AA33);
                cy += lineH;
            }
        }
        if (!lobbySignals.isEmpty()) {
            RenderUtils.drawText(g, "Lobby signals:", m, cy, 0xFFBB4444);
            cy += lineH;
            for (SessionConfidence.Signal s : lobbySignals) {
                RenderUtils.drawText(g, String.format("  -%.2f  %s", s.weight(), s.reason()), m, cy, 0xFFAA3333);
                cy += lineH;
            }
        }
        cy += 4;

        // ── Plugins ──
        List<Map.Entry<String, Double>> pluginsByConf = session != null
                ? session.getPluginsByConfidence() : Collections.emptyList();
        cy = drawSectionHeader(g, cs, "Plugins (" + pluginsByConf.size() + ")", m, cy, contentW);

        if (!pluginsByConf.isEmpty()) {
            int cols = 3;
            int colW = contentW / cols;
            int col = 0;
            int rowY = cy;

            for (Map.Entry<String, Double> entry : pluginsByConf) {
                String name = entry.getKey();
                double pConf = entry.getValue();
                int pColor = pConf > 0.8 ? 0xFF44BB44 : pConf > 0.5 ? 0xFFBBBB44 : 0xFFBB8844;
                String pct = String.format("%.0f%%", pConf * 100);

                int colX = m + col * colW;
                String displayName = RenderUtils.trimToWidth(name, colW - RenderUtils.scaledTextWidth("  100%") - 4);
                RenderUtils.drawText(g, displayName, colX, rowY, cs.textPrimary());
                RenderUtils.drawText(g, pct, colX + colW - RenderUtils.scaledTextWidth(pct) - 4, rowY, pColor);

                col++;
                if (col >= cols) {
                    col = 0;
                    rowY += lineH;
                }
            }
            if (col > 0) rowY += lineH; // finish incomplete row
            cy = rowY;
        } else {
            RenderUtils.drawText(g, "No plugins detected yet.", m, cy, cs.textSecondary());
            cy += lineH;
        }
        cy += 4;

        // ── Detected Addresses ──
        Set<String> addresses = session != null ? session.getDetectedAddresses() : Collections.emptySet();
        if (!addresses.isEmpty()) {
            cy = drawSectionHeader(g, cs, "Detected Addresses (" + addresses.size() + ")", m, cy, contentW);
            int ax = m;
            for (String addr : addresses) {
                int aw = RenderUtils.scaledTextWidth(addr) + 12;
                if (ax + aw > m + contentW && ax > m) {
                    ax = m;
                    cy += lineH;
                }
                RenderUtils.drawText(g, addr, ax, cy, cs.textPrimary());
                ax += aw;
            }
            cy += lineH + 4;
        }

        // ── World History ──
        List<ServerLogData.WorldEntry> worlds = session != null ? session.getWorlds() : Collections.emptyList();
        if (!worlds.isEmpty()) {
            cy = drawSectionHeader(g, cs, "World History (" + worlds.size() + ")", m, cy, contentW);
            for (ServerLogData.WorldEntry w : worlds) {
                RenderUtils.drawText(g, w.timestamp() + "  " + w.dimension(), m, cy, cs.textSecondary());
                cy += lineH;
            }
            cy += 4;
        }

        // ── Bottom buttons ──
        cy += 8;
        int btnW = RenderUtils.scaledTextWidth("Copy Report") + 12;
        int btnX = (width - btnW * 2 - 10) / 2;
        reportCopyBtnX = btnX;
        reportCopyBtnY = cy;
        reportCopyBtnW = btnW;
        boolean copyHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= cy && mouseY < cy + 14;
        RenderUtils.drawRect(g, btnX, cy, btnW, 14, copyHover ? cs.buttonHover() : cs.taskbarButton());
        RenderUtils.drawText(g, "Copy Report", btnX + 6, cy + 2, cs.textPrimary());

        int btn2X = btnX + btnW + 10;
        int btn2W = RenderUtils.scaledTextWidth("Export JSON") + 12;
        reportExportBtnX = btn2X;
        reportExportBtnY = cy;
        reportExportBtnW = btn2W;
        boolean exportHover = mouseX >= btn2X && mouseX < btn2X + btn2W && mouseY >= cy && mouseY < cy + 14;
        RenderUtils.drawRect(g, btn2X, cy, btn2W, 14, exportHover ? cs.buttonHover() : cs.taskbarButton());
        RenderUtils.drawText(g, "Export JSON", btn2X + 6, cy + 2, cs.textPrimary());

        cy += 14 + m;

        RenderUtils.disableScissor(g);

        // Update max scroll
        int totalContentH = cy + (int) reportScroll;
        reportMaxScroll = Math.max(0, totalContentH - height);
        reportScroll = Math.max(0, Math.min(reportScroll, reportMaxScroll));
    }

    // Report button hit areas (updated each frame by renderReport)
    private int reportCopyBtnX, reportCopyBtnY, reportCopyBtnW;
    private int reportExportBtnX, reportExportBtnY, reportExportBtnW;

    private int drawSectionHeader(GuiGraphics g, ColorScheme cs, String title, int x, int cy, int contentW) {
        String header = "\u258C " + title;
        int textW = RenderUtils.scaledTextWidth(header);
        RenderUtils.drawText(g, header, x, cy, cs.accent());
        int lineY = cy + RenderUtils.scaledFontHeight() / 2;
        int lineStartX = x + textW + 2;
        if (lineStartX < x + contentW) {
            RenderUtils.drawHLine(g, lineStartX, lineY, x + contentW - lineStartX, cs.separator());
        }
        return cy + REPORT_LINE_H + 2;
    }

    private int drawKVPair(GuiGraphics g, ColorScheme cs, int x, int cy, int halfW,
                           String k1, String v1, String k2, String v2) {
        RenderUtils.drawText(g, k1 + ": ", x, cy, cs.textSecondary());
        RenderUtils.drawText(g, v1, x + RenderUtils.scaledTextWidth(k1 + ": "), cy, cs.textPrimary());
        RenderUtils.drawText(g, k2 + ": ", x + halfW, cy, cs.textSecondary());
        RenderUtils.drawText(g, v2, x + halfW + RenderUtils.scaledTextWidth(k2 + ": "), cy, cs.textPrimary());
        return cy + REPORT_LINE_H;
    }

    private int drawKVSingle(GuiGraphics g, ColorScheme cs, int x, int cy, String key, String value) {
        RenderUtils.drawText(g, key + ": ", x, cy, cs.textSecondary());
        RenderUtils.drawText(g, value, x + RenderUtils.scaledTextWidth(key + ": "), cy, cs.textPrimary());
        return cy + REPORT_LINE_H;
    }

    private boolean handleReportClick(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        // Back button (top left)
        int backW = RenderUtils.scaledTextWidth("< Back");
        int backY = REPORT_MARGIN - (int) reportScroll;
        if (mouseX >= REPORT_MARGIN && mouseX < REPORT_MARGIN + backW
                && mouseY >= backY && mouseY < backY + REPORT_LINE_H) {
            reportMode = false;
            return true;
        }

        // Copy Report button
        if (mouseX >= reportCopyBtnX && mouseX < reportCopyBtnX + reportCopyBtnW
                && mouseY >= reportCopyBtnY && mouseY < reportCopyBtnY + 14) {
            if (session != null) {
                String text = LogExporter.exportClipboard(session.toLogData(), eventBus.getEvents());
                Minecraft.getInstance().keyboardHandler.setClipboard(text);
                CopyToast.show("Report copied");
            }
            return true;
        }

        // Export JSON button
        if (mouseX >= reportExportBtnX && mouseX < reportExportBtnX + reportExportBtnW
                && mouseY >= reportExportBtnY && mouseY < reportExportBtnY + 14) {
            if (session != null) {
                ServerLogData data = session.toLogData();
                String json = LogExporter.exportJson(data, eventBus.getEvents());
                try {
                    Path path = LogExporter.saveToFile(json, "json");
                    eventBus.post(new LogEvent(LogEvent.Type.SYSTEM, "Exported: " + path));
                } catch (IOException e) {
                    eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Export failed: " + e.getMessage()));
                }
            }
            return true;
        }

        return true; // consume all clicks in report mode
    }

    private void openExportFolder() {
        try {
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve("archivist").resolve("exports");
            java.nio.file.Files.createDirectories(dir);
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception e) {
            eventBus.post(new LogEvent(LogEvent.Type.ERROR, "Failed to open folder: " + e.getMessage()));
        }
    }

    private void applyTheme(String name) {
        if (name == null) return;
        ColorScheme theme = ThemeManager.getInstance().getTheme(name);
        if (theme != null) ColorScheme.setActiveDirect(theme);
    }

    private static Taskbar.Position parseTaskbarPosition(String value) {
        if (value == null) return Taskbar.Position.BOTTOM;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "top" -> Taskbar.Position.TOP;
            case "left" -> Taskbar.Position.LEFT;
            default -> Taskbar.Position.BOTTOM;
        };
    }

    @Override
    public void onClose() {
        if (config.guiAnimations && screenAnimState != ScreenAnimState.CLOSING) {
            // Start closing animation; defer actual close
            closedIntentionally = true;
            if (config.taskbarSlideAnimation) {
                taskbar.startSlideOut();
            }
            if (screenAnimState == ScreenAnimState.OPENING) {
                // Reverse mid-open
                screenAnimState = ScreenAnimState.CLOSING;
                screenAnimProgress = 1f - screenAnimProgress;
            } else {
                screenAnimState = ScreenAnimState.CLOSING;
                screenAnimProgress = 0f;
            }
            return;
        }
        performActualClose();
    }

    private void performActualClose() {
        closedIntentionally = true;
        saveWindowStates();
        ConfigManager.save(config);
        // Reset cursor to default
        long windowHandle = Minecraft.getInstance().getWindow()./*? if >=1.21.9 {*/handle()/*?} else {*//*getWindow()*//*?}*/;
        GLFW.glfwSetCursor(windowHandle, 0);
        lastCursorType = DraggableWindow.CursorType.ARROW;
        destroyCursors();
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }

    private void saveWindowStates() {
        Map<String, WindowStateManager.WindowState> states = new HashMap<>();
        for (DraggableWindow w : windows) {
            states.put(w.getId(), new WindowStateManager.WindowState(
                    w.getX(), w.getY(), w.getWidth(), w.getHeight(),
                    w.isVisible(), w.isMinimized()
            ));
        }
        savedWindowStates = states;
        WindowStateManager.save(states);

        // Persist sidebar order for dynamic mode
        if (panelMode && panelLayout != null) {
            config.sidebarOrder = panelLayout.getSidebarOrder();
            ConfigManager.save(config);
        }
    }

    @Override
    public void removed() {
        saveWindowStates();
        if (!closedIntentionally) {
            Minecraft mc = Minecraft.getInstance();
            Screen newScreen = mc.screen;
            mc.execute(() -> {
                if (mc.screen == newScreen && !(newScreen instanceof ArchivistScreen)) {
                    // Reuse this instance if possible, otherwise create new
                    this.parent = newScreen;
                    this.closedIntentionally = false;
                    ArchivistMod.setCachedScreen(this);
                    mc.setScreen(this);
                }
            });
        }
        ConfigManager.save(config);
        destroyCursors();
        super.removed();
    }
}
