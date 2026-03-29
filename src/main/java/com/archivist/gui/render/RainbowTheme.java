package com.archivist.gui.render;

/**
 * Animated color themes that shift hue every tick.
 * Supports multiple variants: full rainbow, pastel hue, and apple-style red/pink.
 */
public class RainbowTheme extends ColorScheme {

    public enum Variant {
        RAINBOW,      // Full spectrum cycling, ~9 sec cycle
        RAINBOW_HUE,  // Pastel/lighter version, same cycle
        RAINBOW_APPLE  // Red → pink → rose cycling with white/pink background
    }

    private float hue = 0f;
    private static final float SPEED = 2f;
    private final Variant variant;

    private int bgAlpha = 0xBB;
    private int titleBarAlpha = 0xDD;
    private int overlayAlpha = 0x40;

    public RainbowTheme() { this(Variant.RAINBOW); }
    public RainbowTheme(Variant variant) { this.variant = variant; }

    public void setAlphas(int bgAlpha, int titleBarAlpha, int overlayAlpha) {
        this.bgAlpha = bgAlpha;
        this.titleBarAlpha = titleBarAlpha;
        this.overlayAlpha = overlayAlpha;
    }

    public int getBgAlpha() { return bgAlpha; }
    public int getTitleBarAlpha() { return titleBarAlpha; }
    public int getOverlayAlpha() { return overlayAlpha; }

    public void tick() {
        hue = (hue + SPEED) % 360f;
    }

    @Override
    public String name() {
        return switch (variant) {
            case RAINBOW -> "Rainbow";
            case RAINBOW_HUE -> "Rainbow Hue";
            case RAINBOW_APPLE -> "Rainbow Apple";
        };
    }

    // ── HSV to packed 0xFFRRGGBB ─────────────────────────────────────────────

    private static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        int ri = (int) ((r + m) * 255 + 0.5f);
        int gi = (int) ((g + m) * 255 + 0.5f);
        int bi = (int) ((b + m) * 255 + 0.5f);
        return rgba(ri, gi, bi, 0xFF);
    }

    // Variant-specific accent
    private int accentColor() {
        return switch (variant) {
            case RAINBOW -> hsvToRgb(hue, 0.8f, 0.9f);
            case RAINBOW_HUE -> hsvToRgb(hue, 0.45f, 0.95f); // pastel
            case RAINBOW_APPLE -> hsvToRgb((hue % 60f) + 330f, 0.75f, 0.95f); // red-pink range (330-390 → 330-30)
        };
    }

    private int bgColor() {
        return switch (variant) {
            case RAINBOW -> hsvToRgb(hue, 0.15f, 0.05f);
            case RAINBOW_HUE -> hsvToRgb(hue, 0.08f, 0.08f);
            case RAINBOW_APPLE -> hsvToRgb((hue % 60f) + 330f, 0.08f, 0.12f); // warm dark
        };
    }

    // ── Window ──────────────────────────────────────────────────────────────

    @Override public int accent()             { return accentColor(); }
    @Override public int accentDim()          { return darken(accentColor(), 0.3f); }
    @Override public int titleBar()           { return withAlpha(lighten(bgColor(), 0.05f), titleBarAlpha); }
    @Override public int titleText()          { return lighten(accentColor(), 0.15f); }
    @Override public int windowBackground()   { return withAlpha(bgColor(), bgAlpha); }
    @Override public int windowBorder()       { return darken(accentColor(), 0.5f); }
    @Override public int windowBorderActive() { return accent(); }
    @Override public int closeButton()        { return 0xFFFF5555; }
    @Override public int minimizeButton()     { return accent(); }

    // ── Taskbar ─────────────────────────────────────────────────────────────

    @Override public int taskbar()            { return withAlpha(darken(bgColor(), 0.2f), titleBarAlpha); }
    @Override public int taskbarButton()      { return titleBar(); }
    @Override public int taskbarButtonActive() { return accentDim(); }
    @Override public int taskbarText()        { return titleText(); }

    // ── Buttons ─────────────────────────────────────────────────────────────

    @Override public int button()             { return titleBar(); }
    @Override public int buttonHover()        { return lighten(bgColor(), 0.1f); }
    @Override public int buttonPressed()      { return accentDim(); }
    @Override public int buttonText()         { return lighten(bgColor(), 0.85f); }

    // ── Text Fields ─────────────────────────────────────────────────────────

    @Override public int textFieldBg()        { return darken(bgColor(), 0.3f); }
    @Override public int textFieldBorder()    { return buttonHover(); }
    @Override public int textFieldFocused()   { return accent(); }
    @Override public int textFieldText()      { return buttonText(); }
    @Override public int cursor()             { return titleText(); }
    @Override public int placeholder()        { return darken(buttonText(), 0.5f); }

    // ── Tabs ────────────────────────────────────────────────────────────────

    @Override public int tab()                { return windowBackground() | 0xFF000000; }
    @Override public int tabActive()          { return accentDim(); }
    @Override public int tabText()            { return darken(buttonText(), 0.5f); }
    @Override public int tabTextActive()      { return 0xFFFFFFFF; }

    // ── Lists ───────────────────────────────────────────────────────────────

    @Override public int listHover()          { return withAlpha(accent(), 0x33); }
    @Override public int listSelected()       { return accentDim(); }
    @Override public int listText()           { return lighten(bgColor(), 0.7f); }
    @Override public int listTextSelected()   { return 0xFFFFFFFF; }

    // ── Scrollbar ───────────────────────────────────────────────────────────

    @Override public int scrollbarTrack()     { return tab(); }
    @Override public int scrollbarThumb()     { return buttonHover(); }
    @Override public int scrollbarHover()     { return accentDim(); }

    // ── General ─────────────────────────────────────────────────────────────

    @Override public int textPrimary()        { return buttonText(); }
    @Override public int textSecondary()      { return tabText(); }
    @Override public int separator()          { return titleBar(); }
    @Override public int screenOverlay()      { return withAlpha(0x00000000, overlayAlpha); }

    // ── Tooltips ────────────────────────────────────────────────────────────

    @Override public int tooltipBg()          { return withAlpha(windowBackground(), 0xEE); }
    @Override public int tooltipBorder()      { return accentDim(); }

    // ── Console Event Colors ────────────────────────────────────────────────

    @Override public int eventConnect()       { return 0xFF55FF55; }
    @Override public int eventDisconnect()    { return 0xFFFF5555; }
    @Override public int eventBrand()         { return lerpColor(0xFF7ADDFF, accentColor(), 0.3f); }
    @Override public int eventPlugin()        { return lerpColor(0xFF40C0E0, accentColor(), 0.3f); }
    @Override public int eventWorld()         { return lerpColor(0xFF55FFDD, accentColor(), 0.3f); }
    @Override public int eventGamemode()      { return 0xFFFFFF55; }
    @Override public int eventPacket()        { return 0xFF888888; }
    @Override public int eventSystem()        { return 0xFFFFFFFF; }
    @Override public int eventError()         { return 0xFFFF4444; }
    @Override public int eventDbSync()        { return lerpColor(0xFFAA88FF, accentColor(), 0.3f); }

    @Override
    public GradientConfig getTitleBarGradient() {
        int top = withAlpha(lighten(bgColor(), 0.08f), titleBarAlpha);
        int bottom = withAlpha(lighten(bgColor(), 0.02f), titleBarAlpha);
        return new GradientConfig(top, bottom);
    }

    @Override
    public GradientConfig getTaskbarGradient() {
        int top = withAlpha(darken(bgColor(), 0.1f), titleBarAlpha);
        int bottom = withAlpha(darken(bgColor(), 0.3f), titleBarAlpha);
        return new GradientConfig(top, bottom);
    }

    // ── Background Gradient ─────────────────────────────────────────────────

    @Override
    public GradientConfig getBackgroundGradient() {
        return switch (variant) {
            case RAINBOW -> {
                int top = withAlpha(hsvToRgb(hue, 0.3f, 0.04f), bgAlpha);
                int bottom = withAlpha(hsvToRgb((hue + 60f) % 360f, 0.3f, 0.08f), bgAlpha);
                yield new GradientConfig(top, bottom);
            }
            case RAINBOW_HUE -> {
                int top = withAlpha(hsvToRgb(hue, 0.15f, 0.06f), bgAlpha);
                int bottom = withAlpha(hsvToRgb((hue + 40f) % 360f, 0.15f, 0.1f), bgAlpha);
                yield new GradientConfig(top, bottom);
            }
            case RAINBOW_APPLE -> {
                float appleHue = (hue % 60f) + 330f;
                int top = withAlpha(hsvToRgb(appleHue, 0.12f, 0.06f), bgAlpha);
                int bottom = withAlpha(hsvToRgb(appleHue + 20f, 0.15f, 0.1f), bgAlpha);
                yield new GradientConfig(top, bottom);
            }
        };
    }
}
