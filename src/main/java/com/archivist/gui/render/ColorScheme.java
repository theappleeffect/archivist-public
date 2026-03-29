package com.archivist.gui.render;

import com.google.gson.JsonObject;

/**
 * Abstract base for all GUI color themes. Every widget reads colors
 * from ColorScheme.get() — no color literals anywhere else.
 *
 * Colors are stored as 0xAARRGGBB ints (Minecraft convention).
 * Themes are swappable at runtime via setActive().
 */
public abstract class ColorScheme {

    private static ColorScheme active;
    private static boolean gradientsEnabled = true;
    private static boolean backgroundGradientEnabled = true;

    public static ColorScheme get() {
        if (active == null) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", "Sunset");
            active = new JsonTheme(obj);
        }
        return active;
    }

    public static void setActive(ColorScheme theme) {
        if (active != null && active != theme) {
            ColorScheme from = active;
            // If already transitioning, use current interpolated state as the starting point
            if (from instanceof TransitionColorScheme tc) {
                from = tc;
            }
            active = new TransitionColorScheme(from, theme);
        } else {
            active = theme;
        }
    }

    public static void setActiveDirect(ColorScheme theme) {
        active = theme;
    }

    public static boolean gradientsEnabled() { return gradientsEnabled; }
    public static void setGradientsEnabled(boolean enabled) { gradientsEnabled = enabled; }

    public static boolean backgroundGradientEnabled() { return backgroundGradientEnabled; }
    public static void setBackgroundGradientEnabled(boolean enabled) { backgroundGradientEnabled = enabled; }

    public abstract String name();

    // ── Window ──────────────────────────────────────────────────────────────
    public abstract int titleBar();
    public abstract int titleText();
    public abstract int closeButton();
    public abstract int minimizeButton();
    public abstract int windowBackground();
    public abstract int windowBorder();
    public abstract int windowBorderActive();

    // ── Taskbar ─────────────────────────────────────────────────────────────
    public abstract int taskbar();
    public abstract int taskbarButton();
    public abstract int taskbarButtonActive();
    public abstract int taskbarText();

    // ── Buttons ─────────────────────────────────────────────────────────────
    public abstract int button();
    public abstract int buttonHover();
    public abstract int buttonPressed();
    public abstract int buttonText();

    // ── Text Fields ─────────────────────────────────────────────────────────
    public abstract int textFieldBg();
    public abstract int textFieldBorder();
    public abstract int textFieldFocused();
    public abstract int textFieldText();
    public abstract int cursor();
    public abstract int placeholder();

    // ── Tabs ────────────────────────────────────────────────────────────────
    public abstract int tab();
    public abstract int tabActive();
    public abstract int tabText();
    public abstract int tabTextActive();

    // ── Lists ───────────────────────────────────────────────────────────────
    public abstract int listHover();
    public abstract int listSelected();
    public abstract int listText();
    public abstract int listTextSelected();

    // ── Scrollbar ───────────────────────────────────────────────────────────
    public abstract int scrollbarTrack();
    public abstract int scrollbarThumb();
    public abstract int scrollbarHover();

    // ── General ─────────────────────────────────────────────────────────────
    public abstract int accent();
    public abstract int accentDim();
    public abstract int textPrimary();
    public abstract int textSecondary();
    public abstract int separator();
    public abstract int screenOverlay();

    // ── Tooltips ──────────────────────────────────────────────────────────────
    public abstract int tooltipBg();
    public abstract int tooltipBorder();

    // ── Console Event Colors ────────────────────────────────────────────────
    public abstract int eventConnect();
    public abstract int eventDisconnect();
    public abstract int eventBrand();
    public abstract int eventPlugin();
    public abstract int eventWorld();
    public abstract int eventGamemode();
    public abstract int eventPacket();
    public abstract int eventSystem();
    public abstract int eventError();
    public abstract int eventDbSync();

    // ── Color Utilities ─────────────────────────────────────────────────────

    /** Pack r, g, b, a (0–255) into 0xAARRGGBB. */
    public static int rgba(int r, int g, int b, int a) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /** Return the color with a replaced alpha (0–255). */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    /** Linear interpolation between two 0xAARRGGBB colors. t in [0,1]. */
    public static int lerpColor(int from, int to, float t) {
        if (t <= 0) return from;
        if (t >= 1) return to;
        int aF = (from >>> 24) & 0xFF, rF = (from >>> 16) & 0xFF, gF = (from >>> 8) & 0xFF, bF = from & 0xFF;
        int aT = (to >>> 24) & 0xFF, rT = (to >>> 16) & 0xFF, gT = (to >>> 8) & 0xFF, bT = to & 0xFF;
        return rgba(
                (int) (rF + (rT - rF) * t),
                (int) (gF + (gT - gF) * t),
                (int) (bF + (bT - bF) * t),
                (int) (aF + (aT - aF) * t)
        );
    }

    /** Lighten a color by the given amount (0.0-1.0). */
    public static int lighten(int color, float amount) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.min(255, (int) (((color >>> 16) & 0xFF) + 255 * amount));
        int g = Math.min(255, (int) (((color >>> 8) & 0xFF) + 255 * amount));
        int b = Math.min(255, (int) ((color & 0xFF) + 255 * amount));
        return rgba(r, g, b, a);
    }

    /** Darken a color by the given amount (0.0-1.0). */
    public static int darken(int color, float amount) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.max(0, (int) (((color >>> 16) & 0xFF) * (1 - amount)));
        int g = Math.max(0, (int) (((color >>> 8) & 0xFF) * (1 - amount)));
        int b = Math.max(0, (int) ((color & 0xFF) * (1 - amount)));
        return rgba(r, g, b, a);
    }

    /** WCAG relative luminance of a color (0.0-1.0). */
    public static double luminance(int color) {
        double r = ((color >>> 16) & 0xFF) / 255.0;
        double g = ((color >>> 8) & 0xFF) / 255.0;
        double b = (color & 0xFF) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG contrast ratio between two colors (1.0 to 21.0). */
    public static double contrastRatio(int color1, int color2) {
        double l1 = luminance(color1);
        double l2 = luminance(color2);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** Get gradient for title bars, or null to use solid titleBar() color. */
    public GradientConfig getTitleBarGradient() { return null; }

    /** Get gradient for buttons, or null to use solid button() color. */
    public GradientConfig getButtonGradient() { return null; }

    /** Get gradient for taskbar, or null to use solid taskbar() color. */
    public GradientConfig getTaskbarGradient() { return null; }

    /** Get gradient for window backgrounds, or null to use solid windowBackground() color. */
    public GradientConfig getWindowBackgroundGradient() { return null; }

    /** Get the background gradient for full-screen rendering, or null for none. */
    public GradientConfig getBackgroundGradient() {
        return null;
    }

    /** Called each frame to advance animations (transitions, rainbow, animated gradients). */
    public void tick() { /* no-op by default */ }

    /** Get the color for a LogEvent type. */
    public int eventColor(com.archivist.data.LogEvent.Type type) {
        return switch (type) {
            case CONNECT -> eventConnect();
            case DISCONNECT -> eventDisconnect();
            case BRAND -> eventBrand();
            case PLUGIN -> eventPlugin();
            case WORLD -> eventWorld();
            case GAMEMODE -> eventGamemode();
            case PACKET -> eventPacket();
            case SYSTEM -> eventSystem();
            case ERROR -> eventError();
            case DB_SYNC -> eventDbSync();
        };
    }
}
