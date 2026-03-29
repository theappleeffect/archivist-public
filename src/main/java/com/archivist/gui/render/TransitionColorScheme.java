package com.archivist.gui.render;

/**
 * Wraps two ColorSchemes and lerps all colors between them over a fixed duration.
 * Installed transparently by ColorScheme.setActive() — widgets see interpolated values.
 */
public class TransitionColorScheme extends ColorScheme {

    private static final long DURATION_MS = 300;

    private final ColorScheme from;
    private final ColorScheme to;
    private final long startTimeMs;
    private float progress = 0f;

    TransitionColorScheme(ColorScheme from, ColorScheme to) {
        this.from = from;
        this.to = to;
        this.startTimeMs = System.currentTimeMillis();
    }

    @Override
    public void tick() {
        progress = Math.min(1f, (System.currentTimeMillis() - startTimeMs) / (float) DURATION_MS);
        from.tick();
        to.tick();
        if (progress >= 1f) {
            ColorScheme.setActiveDirect(to);
        }
    }

    private int lerp(int a, int b) {
        return ColorScheme.lerpColor(a, b, progress);
    }

    private GradientConfig lerpGradient(GradientConfig fromG, GradientConfig toG,
                                        int fromFallback, int toFallback) {
        if (fromG == null && toG == null) return null;
        int ft = fromG != null ? fromG.topColor() : fromFallback;
        int fb = fromG != null ? fromG.bottomColor() : fromFallback;
        int tt = toG != null ? toG.topColor() : toFallback;
        int tb = toG != null ? toG.bottomColor() : toFallback;
        return new GradientConfig(lerp(ft, tt), lerp(fb, tb));
    }

    @Override public String name() { return to.name(); }

    // ── Window ──
    @Override public int titleBar()          { return lerp(from.titleBar(), to.titleBar()); }
    @Override public int titleText()         { return lerp(from.titleText(), to.titleText()); }
    @Override public int closeButton()       { return lerp(from.closeButton(), to.closeButton()); }
    @Override public int minimizeButton()    { return lerp(from.minimizeButton(), to.minimizeButton()); }
    @Override public int windowBackground()  { return lerp(from.windowBackground(), to.windowBackground()); }
    @Override public int windowBorder()      { return lerp(from.windowBorder(), to.windowBorder()); }
    @Override public int windowBorderActive(){ return lerp(from.windowBorderActive(), to.windowBorderActive()); }

    // ── Taskbar ──
    @Override public int taskbar()           { return lerp(from.taskbar(), to.taskbar()); }
    @Override public int taskbarButton()     { return lerp(from.taskbarButton(), to.taskbarButton()); }
    @Override public int taskbarButtonActive(){ return lerp(from.taskbarButtonActive(), to.taskbarButtonActive()); }
    @Override public int taskbarText()       { return lerp(from.taskbarText(), to.taskbarText()); }

    // ── Buttons ──
    @Override public int button()            { return lerp(from.button(), to.button()); }
    @Override public int buttonHover()       { return lerp(from.buttonHover(), to.buttonHover()); }
    @Override public int buttonPressed()     { return lerp(from.buttonPressed(), to.buttonPressed()); }
    @Override public int buttonText()        { return lerp(from.buttonText(), to.buttonText()); }

    // ── Text Fields ──
    @Override public int textFieldBg()       { return lerp(from.textFieldBg(), to.textFieldBg()); }
    @Override public int textFieldBorder()   { return lerp(from.textFieldBorder(), to.textFieldBorder()); }
    @Override public int textFieldFocused()  { return lerp(from.textFieldFocused(), to.textFieldFocused()); }
    @Override public int textFieldText()     { return lerp(from.textFieldText(), to.textFieldText()); }
    @Override public int cursor()            { return lerp(from.cursor(), to.cursor()); }
    @Override public int placeholder()       { return lerp(from.placeholder(), to.placeholder()); }

    // ── Tabs ──
    @Override public int tab()               { return lerp(from.tab(), to.tab()); }
    @Override public int tabActive()         { return lerp(from.tabActive(), to.tabActive()); }
    @Override public int tabText()           { return lerp(from.tabText(), to.tabText()); }
    @Override public int tabTextActive()     { return lerp(from.tabTextActive(), to.tabTextActive()); }

    // ── Lists ──
    @Override public int listHover()         { return lerp(from.listHover(), to.listHover()); }
    @Override public int listSelected()      { return lerp(from.listSelected(), to.listSelected()); }
    @Override public int listText()          { return lerp(from.listText(), to.listText()); }
    @Override public int listTextSelected()  { return lerp(from.listTextSelected(), to.listTextSelected()); }

    // ── Scrollbar ──
    @Override public int scrollbarTrack()    { return lerp(from.scrollbarTrack(), to.scrollbarTrack()); }
    @Override public int scrollbarThumb()    { return lerp(from.scrollbarThumb(), to.scrollbarThumb()); }
    @Override public int scrollbarHover()    { return lerp(from.scrollbarHover(), to.scrollbarHover()); }

    // ── General ──
    @Override public int accent()            { return lerp(from.accent(), to.accent()); }
    @Override public int accentDim()         { return lerp(from.accentDim(), to.accentDim()); }
    @Override public int textPrimary()       { return lerp(from.textPrimary(), to.textPrimary()); }
    @Override public int textSecondary()     { return lerp(from.textSecondary(), to.textSecondary()); }
    @Override public int separator()         { return lerp(from.separator(), to.separator()); }
    @Override public int screenOverlay()     { return lerp(from.screenOverlay(), to.screenOverlay()); }

    // ── Tooltips ──
    @Override public int tooltipBg()         { return lerp(from.tooltipBg(), to.tooltipBg()); }
    @Override public int tooltipBorder()     { return lerp(from.tooltipBorder(), to.tooltipBorder()); }

    // ── Events ──
    @Override public int eventConnect()      { return lerp(from.eventConnect(), to.eventConnect()); }
    @Override public int eventDisconnect()   { return lerp(from.eventDisconnect(), to.eventDisconnect()); }
    @Override public int eventBrand()        { return lerp(from.eventBrand(), to.eventBrand()); }
    @Override public int eventPlugin()       { return lerp(from.eventPlugin(), to.eventPlugin()); }
    @Override public int eventWorld()        { return lerp(from.eventWorld(), to.eventWorld()); }
    @Override public int eventGamemode()     { return lerp(from.eventGamemode(), to.eventGamemode()); }
    @Override public int eventPacket()       { return lerp(from.eventPacket(), to.eventPacket()); }
    @Override public int eventSystem()       { return lerp(from.eventSystem(), to.eventSystem()); }
    @Override public int eventError()        { return lerp(from.eventError(), to.eventError()); }
    @Override public int eventDbSync()       { return lerp(from.eventDbSync(), to.eventDbSync()); }

    // ── Gradients ──
    @Override public GradientConfig getTitleBarGradient() {
        return lerpGradient(from.getTitleBarGradient(), to.getTitleBarGradient(),
                from.titleBar(), to.titleBar());
    }
    @Override public GradientConfig getButtonGradient() {
        return lerpGradient(from.getButtonGradient(), to.getButtonGradient(),
                from.button(), to.button());
    }
    @Override public GradientConfig getTaskbarGradient() {
        return lerpGradient(from.getTaskbarGradient(), to.getTaskbarGradient(),
                from.taskbar(), to.taskbar());
    }
    @Override public GradientConfig getWindowBackgroundGradient() {
        return lerpGradient(from.getWindowBackgroundGradient(), to.getWindowBackgroundGradient(),
                from.windowBackground(), to.windowBackground());
    }
    @Override public GradientConfig getBackgroundGradient() {
        return lerpGradient(from.getBackgroundGradient(), to.getBackgroundGradient(),
                from.screenOverlay(), to.screenOverlay());
    }
}
