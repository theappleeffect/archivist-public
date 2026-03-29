package com.archivist.gui.render;

import com.google.gson.JsonObject;

/**
 * A ColorScheme backed by a parsed JSON object.
 * All color values are read from the JSON at construction time.
 */
public class JsonTheme extends ColorScheme {

    private final String themeName;
    private final JsonObject colors;
    private final JsonObject events;
    private final GradientConfig gradient;
    private final GradientConfig titleBarGradient;
    private final GradientConfig buttonGradient;
    private final GradientConfig taskbarGradient;
    private final GradientConfig windowBgGradient;

    // Animated gradient support
    private final boolean animatedGradient;
    private final float animationSpeed;
    private float animHue = 0f;

    public JsonTheme(JsonObject root) {
        this.themeName = root.get("name").getAsString();
        this.colors = root.has("colors") ? root.getAsJsonObject("colors") : new JsonObject();
        this.events = root.has("events") ? root.getAsJsonObject("events") : new JsonObject();

        if (root.has("gradient")) {
            JsonObject g = root.getAsJsonObject("gradient");
            this.gradient = new GradientConfig(
                    parseColor(g, "top", 0xBB000810),
                    parseColor(g, "bottom", 0xBB001020)
            );
        } else {
            this.gradient = null;
        }

        if (root.has("gradients")) {
            JsonObject grads = root.getAsJsonObject("gradients");
            this.titleBarGradient = parseGradient(grads, "titleBar");
            this.buttonGradient = parseGradient(grads, "button");
            this.taskbarGradient = parseGradient(grads, "taskbar");
            this.windowBgGradient = parseGradient(grads, "windowBackground");
        } else {
            this.titleBarGradient = null;
            this.buttonGradient = null;
            this.taskbarGradient = null;
            this.windowBgGradient = null;
        }

        if (root.has("animatedGradient")) {
            var ag = root.get("animatedGradient");
            if (ag.isJsonPrimitive() && ag.getAsJsonPrimitive().isNumber()) {
                this.animatedGradient = true;
                this.animationSpeed = ag.getAsFloat();
            } else {
                this.animatedGradient = ag.isJsonPrimitive() && ag.getAsBoolean();
                this.animationSpeed = 0.5f;
            }
        } else {
            this.animatedGradient = false;
            this.animationSpeed = 0f;
        }
    }

    @Override public String name() { return themeName; }

    // ── Window ──
    @Override public int titleBar()          { return color("titleBar", 0xDD0D1D2D); }
    @Override public int titleText()         { return color("titleText", 0xFF7ADDFF); }
    @Override public int closeButton()       { return color("closeButton", 0xFFFF5555); }
    @Override public int minimizeButton()    { return color("minimizeButton", 0xFF7ADDFF); }
    @Override public int windowBackground()  { return color("windowBackground", 0xBB081018); }
    @Override public int windowBorder()      { return color("windowBorder", 0xFF2A7090); }
    @Override public int windowBorderActive(){ return color("windowBorderActive", 0xFF40C0E0); }

    // ── Taskbar ──
    @Override public int taskbar()           { return color("taskbar", 0xDD060E18); }
    @Override public int taskbarButton()     { return color("taskbarButton", 0xFF0D1D2D); }
    @Override public int taskbarButtonActive(){ return color("taskbarButtonActive", 0xFF2A7090); }
    @Override public int taskbarText()       { return color("taskbarText", 0xFF7ADDFF); }

    // ── Buttons ──
    @Override public int button()            { return color("button", 0xFF0D1D2D); }
    @Override public int buttonHover()       { return color("buttonHover", 0xFF1A3045); }
    @Override public int buttonPressed()     { return color("buttonPressed", 0xFF2A7090); }
    @Override public int buttonText()        { return color("buttonText", 0xFFCCEEFF); }

    // ── Text Fields ──
    @Override public int textFieldBg()       { return color("textFieldBg", 0xFF060A10); }
    @Override public int textFieldBorder()   { return color("textFieldBorder", 0xFF1A3045); }
    @Override public int textFieldFocused()  { return color("textFieldFocused", 0xFF40C0E0); }
    @Override public int textFieldText()     { return color("textFieldText", 0xFFDDEEFF); }
    @Override public int cursor()            { return color("cursor", 0xFF7ADDFF); }
    @Override public int placeholder()       { return color("placeholder", 0xFF2A4060); }

    // ── Tabs ──
    @Override public int tab()               { return color("tab", 0xFF081018); }
    @Override public int tabActive()         { return color("tabActive", 0xFF2A7090); }
    @Override public int tabText()           { return color("tabText", 0xFF406878); }
    @Override public int tabTextActive()     { return color("tabTextActive", 0xFFFFFFFF); }

    // ── Lists ──
    @Override public int listHover()         { return color("listHover", 0x3340C0E0); }
    @Override public int listSelected()      { return color("listSelected", 0xFF2A7090); }
    @Override public int listText()          { return color("listText", 0xFFAAD0EE); }
    @Override public int listTextSelected()  { return color("listTextSelected", 0xFFFFFFFF); }

    // ── Scrollbar ──
    @Override public int scrollbarTrack()    { return color("scrollbarTrack", 0xFF081018); }
    @Override public int scrollbarThumb()    { return color("scrollbarThumb", 0xFF1A3045); }
    @Override public int scrollbarHover()    { return color("scrollbarHover", 0xFF2A7090); }

    // ── General ──
    @Override public int accent()            { return color("accent", 0xFF40C0E0); }
    @Override public int accentDim()         { return color("accentDim", 0xFF2A7090); }
    @Override public int textPrimary()       { return color("textPrimary", 0xFFDDEEFF); }
    @Override public int textSecondary()     { return color("textSecondary", 0xFF406878); }
    @Override public int separator()         { return color("separator", 0xFF0D1D2D); }
    @Override public int screenOverlay()     { return color("screenOverlay", 0x40000000); }

    // ── Tooltips ──
    @Override public int tooltipBg()         { return color("tooltipBg", 0xEE081018); }
    @Override public int tooltipBorder()     { return color("tooltipBorder", 0xFF2A7090); }

    @Override public GradientConfig getTitleBarGradient() {
        return animatedGradient ? shiftGradient(titleBarGradient) : titleBarGradient;
    }
    @Override public GradientConfig getButtonGradient() {
        return animatedGradient ? shiftGradient(buttonGradient) : buttonGradient;
    }
    @Override public GradientConfig getTaskbarGradient() {
        return animatedGradient ? shiftGradient(taskbarGradient) : taskbarGradient;
    }
    @Override public GradientConfig getWindowBackgroundGradient() {
        return animatedGradient ? shiftGradient(windowBgGradient) : windowBgGradient;
    }
    @Override public GradientConfig getBackgroundGradient() {
        return animatedGradient ? shiftGradient(gradient) : gradient;
    }

    @Override
    public void tick() {
        if (animatedGradient) {
            animHue = (animHue + animationSpeed) % 360f;
        }
    }

    public boolean isAnimatedGradient() { return animatedGradient; }
    public float getAnimationSpeed() { return animationSpeed; }

    private GradientConfig shiftGradient(GradientConfig gc) {
        if (gc == null) return null;
        return new GradientConfig(shiftHue(gc.topColor(), animHue), shiftHue(gc.bottomColor(), animHue));
    }

    private static int shiftHue(int argb, float hueOffset) {
        int a = (argb >>> 24) & 0xFF;
        float[] hsl = ThemeGenerator.rgbToHsl(argb);
        hsl[0] = (hsl[0] + hueOffset / 360f) % 1.0f;
        if (hsl[0] < 0) hsl[0] += 1.0f;
        int shifted = ThemeGenerator.hslToArgb(hsl[0], hsl[1], hsl[2], 1.0f);
        return ColorScheme.withAlpha(shifted, a);
    }

    // ── Console Event Colors ──
    @Override public int eventConnect()      { return event("connect", 0xFF55FF55); }
    @Override public int eventDisconnect()   { return event("disconnect", 0xFFFF5555); }
    @Override public int eventBrand()        { return event("brand", 0xFF7ADDFF); }
    @Override public int eventPlugin()       { return event("plugin", 0xFF40C0E0); }
    @Override public int eventWorld()        { return event("world", 0xFF55FFDD); }
    @Override public int eventGamemode()     { return event("gamemode", 0xFFFFFF55); }
    @Override public int eventPacket()       { return event("packet", 0xFF888888); }
    @Override public int eventSystem()       { return event("system", 0xFFFFFFFF); }
    @Override public int eventError()        { return event("error", 0xFFFF4444); }
    @Override public int eventDbSync()       { return event("dbSync", 0xFFAA88FF); }

    // ── Helpers ──

    private int color(String key, int fallback) {
        return parseColor(colors, key, fallback);
    }

    private int event(String key, int fallback) {
        return parseColor(events, key, fallback);
    }

    private static GradientConfig parseGradient(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) return null;
        JsonObject g = parent.getAsJsonObject(key);
        if (!g.has("top") || !g.has("bottom")) return null;
        return new GradientConfig(
                parseHexColor(g.get("top").getAsString(), 0),
                parseHexColor(g.get("bottom").getAsString(), 0)
        );
    }

    /** Parse a hex color string (0x..., #..., or raw hex) to ARGB int. */
    public static int parseHexColor(String val, int fallback) {
        try {
            if (val.startsWith("0x") || val.startsWith("0X")) {
                return (int) Long.parseLong(val.substring(2), 16);
            }
            if (val.startsWith("#")) {
                val = val.substring(1);
                if (val.length() == 6) return (int) (0xFF000000L | Long.parseLong(val, 16));
                return (int) Long.parseLong(val, 16);
            }
            if (val.length() == 6) return (int) (0xFF000000L | Long.parseLong(val, 16));
            return (int) Long.parseLong(val, 16);
        } catch (Exception e) { return fallback; }
    }

    static int parseColor(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        try {
            String val = obj.get(key).getAsString();
            if (val.startsWith("0x") || val.startsWith("0X")) {
                return (int) Long.parseLong(val.substring(2), 16);
            }
            if (val.startsWith("#")) {
                val = val.substring(1);
                if (val.length() == 6) {
                    return (int) (0xFF000000L | Long.parseLong(val, 16));
                }
                return (int) Long.parseLong(val, 16);
            }
            return (int) Long.parseLong(val, 16);
        } catch (Exception e) {
            return fallback;
        }
    }
}
