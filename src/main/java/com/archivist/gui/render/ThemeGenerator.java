package com.archivist.gui.render;

import com.google.gson.JsonObject;

/**
 * Generates complete color themes from a single accent color using HSL color space.
 */
public final class ThemeGenerator {

    private ThemeGenerator() {}

    /**
     * Generate a complete theme from a single accent color.
     * @param name the theme name
     * @param accentArgb the accent color in 0xAARRGGBB format
     * @return a ColorScheme with all 45+ color slots derived from the accent
     */
    public static ColorScheme generate(String name, int accentArgb) {
        float[] hsl = rgbToHsl(accentArgb);
        float h = hsl[0], s = hsl[1], l = hsl[2];

        JsonObject root = new JsonObject();
        root.addProperty("name", name);

        JsonObject colors = new JsonObject();

        // Window colors - dark backgrounds derived from accent hue
        colors.addProperty("titleBar", hex(hslToArgb(h, s * 0.3f, 0.10f, 0.87f)));
        colors.addProperty("titleText", hex(hslToArgb(h, s * 0.6f, 0.75f, 1.0f)));
        colors.addProperty("closeButton", hex(hslToArgb(0f, 0.8f, 0.65f, 1.0f)));  // red-ish
        colors.addProperty("minimizeButton", hex(hslToArgb(h, s * 0.6f, 0.75f, 1.0f)));
        colors.addProperty("windowBackground", hex(hslToArgb(h, s * 0.4f, 0.05f, 0.73f)));
        colors.addProperty("windowBorder", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));
        colors.addProperty("windowBorderActive", hex(hslToArgb(h, s * 0.8f, 0.55f, 1.0f)));

        // Taskbar
        colors.addProperty("taskbar", hex(hslToArgb(h, s * 0.3f, 0.05f, 0.87f)));
        colors.addProperty("taskbarButton", hex(hslToArgb(h, s * 0.3f, 0.10f, 1.0f)));
        colors.addProperty("taskbarButtonActive", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));
        colors.addProperty("taskbarText", hex(hslToArgb(h, s * 0.6f, 0.75f, 1.0f)));

        // Buttons
        colors.addProperty("button", hex(hslToArgb(h, s * 0.3f, 0.10f, 1.0f)));
        colors.addProperty("buttonHover", hex(hslToArgb(h, s * 0.4f, 0.18f, 1.0f)));
        colors.addProperty("buttonPressed", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));
        colors.addProperty("buttonText", hex(hslToArgb(h, s * 0.3f, 0.85f, 1.0f)));

        // Text Fields
        colors.addProperty("textFieldBg", hex(hslToArgb(h, s * 0.3f, 0.03f, 1.0f)));
        colors.addProperty("textFieldBorder", hex(hslToArgb(h, s * 0.4f, 0.18f, 1.0f)));
        colors.addProperty("textFieldFocused", hex(hslToArgb(h, s * 0.8f, 0.55f, 1.0f)));
        colors.addProperty("textFieldText", hex(hslToArgb(h, s * 0.2f, 0.90f, 1.0f)));
        colors.addProperty("cursor", hex(hslToArgb(h, s * 0.6f, 0.75f, 1.0f)));
        colors.addProperty("placeholder", hex(hslToArgb(h, s * 0.4f, 0.25f, 1.0f)));

        // Tabs
        colors.addProperty("tab", hex(hslToArgb(h, s * 0.3f, 0.05f, 1.0f)));
        colors.addProperty("tabActive", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));
        colors.addProperty("tabText", hex(hslToArgb(h, s * 0.4f, 0.30f, 1.0f)));
        colors.addProperty("tabTextActive", hex(0xFFFFFFFF));

        // Lists
        colors.addProperty("listHover", hex(hslToArgb(h, s * 0.8f, 0.55f, 0.2f)));
        colors.addProperty("listSelected", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));
        colors.addProperty("listText", hex(hslToArgb(h, s * 0.3f, 0.72f, 1.0f)));
        colors.addProperty("listTextSelected", hex(0xFFFFFFFF));

        // Scrollbar
        colors.addProperty("scrollbarTrack", hex(hslToArgb(h, s * 0.3f, 0.05f, 1.0f)));
        colors.addProperty("scrollbarThumb", hex(hslToArgb(h, s * 0.4f, 0.18f, 1.0f)));
        colors.addProperty("scrollbarHover", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));

        // General
        colors.addProperty("accent", hex(accentArgb));
        colors.addProperty("accentDim", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));
        colors.addProperty("textPrimary", hex(hslToArgb(h, s * 0.15f, 0.90f, 1.0f)));
        colors.addProperty("textSecondary", hex(hslToArgb(h, s * 0.3f, 0.35f, 1.0f)));
        colors.addProperty("separator", hex(hslToArgb(h, s * 0.3f, 0.10f, 1.0f)));
        colors.addProperty("screenOverlay", hex(0x40000000));

        // Tooltips
        colors.addProperty("tooltipBg", hex(hslToArgb(h, s * 0.4f, 0.05f, 0.93f)));
        colors.addProperty("tooltipBorder", hex(hslToArgb(h, s * 0.6f, 0.35f, 1.0f)));

        root.add("colors", colors);

        // Event colors - keep universal
        JsonObject events = new JsonObject();
        events.addProperty("connect", hex(0xFF55FF55));
        events.addProperty("disconnect", hex(0xFFFF5555));
        events.addProperty("brand", hex(hslToArgb(h, s * 0.6f, 0.75f, 1.0f)));
        events.addProperty("plugin", hex(hslToArgb(h, s * 0.8f, 0.55f, 1.0f)));
        events.addProperty("world", hex(0xFF55FFDD));
        events.addProperty("gamemode", hex(0xFFFFFF55));
        events.addProperty("packet", hex(0xFF888888));
        events.addProperty("system", hex(0xFFFFFFFF));
        events.addProperty("error", hex(0xFFFF4444));
        events.addProperty("dbSync", hex(0xFFAA88FF));
        root.add("events", events);

        // Gradient
        JsonObject gradient = new JsonObject();
        gradient.addProperty("top", hex(hslToArgb(h, s * 0.4f, 0.02f, 0.73f)));
        gradient.addProperty("bottom", hex(hslToArgb(h, s * 0.4f, 0.05f, 0.73f)));
        root.add("gradient", gradient);

        JsonObject gradients = new JsonObject();
        JsonObject tbGrad = new JsonObject();
        tbGrad.addProperty("top", hex(hslToArgb(h, s * 0.3f, 0.12f, 0.87f)));
        tbGrad.addProperty("bottom", hex(hslToArgb(h, s * 0.3f, 0.08f, 0.87f)));
        gradients.add("titleBar", tbGrad);
        JsonObject btnGrad = new JsonObject();
        btnGrad.addProperty("top", hex(hslToArgb(h, s * 0.3f, 0.12f, 1.0f)));
        btnGrad.addProperty("bottom", hex(hslToArgb(h, s * 0.3f, 0.08f, 1.0f)));
        gradients.add("button", btnGrad);
        JsonObject taskGrad = new JsonObject();
        taskGrad.addProperty("top", hex(hslToArgb(h, s * 0.3f, 0.07f, 0.87f)));
        taskGrad.addProperty("bottom", hex(hslToArgb(h, s * 0.3f, 0.03f, 0.87f)));
        gradients.add("taskbar", taskGrad);
        root.add("gradients", gradients);

        return new JsonTheme(root);
    }

    /**
     * Generate a complete theme from three colors: accent, background, and text.
     * All 45+ slots are derived from these three via HSL manipulation.
     */
    public static ColorScheme generate(String name, int accentArgb, int bgArgb, int textArgb) {
        return generate(name, accentArgb, bgArgb, textArgb, -1, -1, -1);
    }

    /**
     * Generate a theme with optional alpha overrides for GUI backgrounds, title bars, and screen overlay.
     * Pass -1 for any alpha to use defaults.
     */
    public static ColorScheme generate(String name, int accentArgb, int bgArgb, int textArgb,
                                        int bgAlpha, int titleBarAlpha, int overlayAlpha) {
        float[] aHsl = rgbToHsl(accentArgb);
        float[] bHsl = rgbToHsl(bgArgb);
        float[] tHsl = rgbToHsl(textArgb);
        float ah = aHsl[0], as = aHsl[1], al = aHsl[2];
        float bh = bHsl[0], bs = bHsl[1], bl = bHsl[2];
        float th = tHsl[0], ts = tHsl[1], tl = tHsl[2];

        int defBgAlpha = bgAlpha >= 0 ? bgAlpha : 0xBB;
        int defTbAlpha = titleBarAlpha >= 0 ? titleBarAlpha : 0xDD;
        int defOvAlpha = overlayAlpha >= 0 ? overlayAlpha : 0x40;

        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        JsonObject colors = new JsonObject();

        // Primary (accent) derived
        colors.addProperty("accent", hex(accentArgb | 0xFF000000));
        colors.addProperty("accentDim", hex(hslToArgb(ah, as * 0.6f, al * 0.6f, 1.0f)));
        colors.addProperty("button", hex(hslToArgb(bh, bs * 0.5f, bl * 1.8f, 1.0f)));
        colors.addProperty("buttonHover", hex(hslToArgb(ah, as * 0.4f, Math.min(1, bl * 2.5f), 1.0f)));
        colors.addProperty("buttonPressed", hex(hslToArgb(ah, as * 0.6f, al * 0.7f, 1.0f)));
        colors.addProperty("tabActive", hex(hslToArgb(ah, as * 0.6f, al * 0.7f, 1.0f)));
        colors.addProperty("tabTextActive", hex(0xFFFFFFFF));
        colors.addProperty("listSelected", hex(hslToArgb(ah, as * 0.6f, al * 0.7f, 1.0f)));
        colors.addProperty("scrollbarThumb", hex(hslToArgb(ah, as * 0.4f, Math.min(1, bl * 2.5f), 1.0f)));
        colors.addProperty("scrollbarHover", hex(hslToArgb(ah, as * 0.6f, al * 0.7f, 1.0f)));
        colors.addProperty("closeButton", hex(0xFFFF5555));
        colors.addProperty("minimizeButton", hex(accentArgb | 0xFF000000));
        colors.addProperty("cursor", hex(accentArgb | 0xFF000000));
        colors.addProperty("taskbarButtonActive", hex(hslToArgb(ah, as * 0.6f, al * 0.7f, 1.0f)));
        colors.addProperty("windowBorderActive", hex(accentArgb | 0xFF000000));

        // Secondary (background) derived
        colors.addProperty("windowBackground", hex(ColorScheme.withAlpha(bgArgb | 0xFF000000, defBgAlpha)));
        colors.addProperty("titleBar", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.5f, bl * 1.5f, 1.0f), defTbAlpha)));
        colors.addProperty("taskbar", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.4f, bl * 0.8f, 1.0f), defTbAlpha)));
        colors.addProperty("tab", hex(hslToArgb(bh, bs * 0.4f, bl * 0.8f, 1.0f)));
        colors.addProperty("textFieldBg", hex(hslToArgb(bh, bs * 0.3f, bl * 0.5f, 1.0f)));
        colors.addProperty("listHover", hex(ColorScheme.withAlpha(accentArgb | 0xFF000000, 0x33)));
        colors.addProperty("scrollbarTrack", hex(hslToArgb(bh, bs * 0.3f, bl * 0.8f, 1.0f)));
        colors.addProperty("tooltipBg", hex(ColorScheme.withAlpha(bgArgb | 0xFF000000, 0xEE)));
        colors.addProperty("tooltipBorder", hex(hslToArgb(ah, as * 0.6f, al * 0.7f, 1.0f)));
        colors.addProperty("separator", hex(hslToArgb(bh, bs * 0.3f, bl * 1.8f, 1.0f)));
        colors.addProperty("screenOverlay", hex(ColorScheme.withAlpha(0x00000000, defOvAlpha)));

        // Misc (text) derived
        colors.addProperty("textPrimary", hex(textArgb | 0xFF000000));
        colors.addProperty("textSecondary", hex(hslToArgb(th, ts * 0.5f, tl * 0.5f, 1.0f)));
        colors.addProperty("titleText", hex(textArgb | 0xFF000000));
        colors.addProperty("buttonText", hex(textArgb | 0xFF000000));
        colors.addProperty("tabText", hex(hslToArgb(th, ts * 0.5f, tl * 0.5f, 1.0f)));
        colors.addProperty("textFieldText", hex(textArgb | 0xFF000000));
        colors.addProperty("listText", hex(hslToArgb(th, ts * 0.3f, tl * 0.85f, 1.0f)));
        colors.addProperty("listTextSelected", hex(textArgb | 0xFF000000));
        colors.addProperty("taskbarText", hex(textArgb | 0xFF000000));
        colors.addProperty("placeholder", hex(hslToArgb(th, ts * 0.3f, tl * 0.35f, 1.0f)));
        colors.addProperty("textFieldBorder", hex(hslToArgb(th, ts * 0.3f, tl * 0.3f, 1.0f)));
        colors.addProperty("textFieldFocused", hex(accentArgb | 0xFF000000));
        colors.addProperty("windowBorder", hex(hslToArgb(th, ts * 0.3f, tl * 0.3f, 1.0f)));
        root.add("colors", colors);

        // Event colors — rotated from accent hue
        JsonObject events = new JsonObject();
        events.addProperty("connect", hex(hslToArgb((ah + 0.33f) % 1.0f, 0.8f, 0.65f, 1.0f)));
        events.addProperty("disconnect", hex(hslToArgb(0f, 0.8f, 0.65f, 1.0f)));
        events.addProperty("brand", hex(hslToArgb(ah, as * 0.7f, 0.75f, 1.0f)));
        events.addProperty("plugin", hex(accentArgb | 0xFF000000));
        events.addProperty("world", hex(hslToArgb((ah + 0.5f) % 1.0f, 0.7f, 0.75f, 1.0f)));
        events.addProperty("gamemode", hex(hslToArgb((ah + 0.17f) % 1.0f, 0.8f, 0.7f, 1.0f)));
        events.addProperty("packet", hex(0xFF888888));
        events.addProperty("system", hex(0xFFFFFFFF));
        events.addProperty("error", hex(0xFFFF4444));
        events.addProperty("dbSync", hex(hslToArgb((ah + 0.75f) % 1.0f, 0.6f, 0.7f, 1.0f)));
        root.add("events", events);

        // Gradient from background
        JsonObject gradient = new JsonObject();
        gradient.addProperty("top", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.4f, bl * 0.4f, 1.0f), defBgAlpha)));
        gradient.addProperty("bottom", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.4f, bl * 0.9f, 1.0f), defBgAlpha)));
        root.add("gradient", gradient);

        JsonObject gradients = new JsonObject();
        JsonObject tbGrad = new JsonObject();
        tbGrad.addProperty("top", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.5f, Math.min(1, bl * 1.8f), 1.0f), defTbAlpha)));
        tbGrad.addProperty("bottom", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.5f, Math.min(1, bl * 1.2f), 1.0f), defTbAlpha)));
        gradients.add("titleBar", tbGrad);
        JsonObject btnGrad = new JsonObject();
        btnGrad.addProperty("top", hex(hslToArgb(bh, bs * 0.5f, Math.min(1, bl * 2.0f), 1.0f)));
        btnGrad.addProperty("bottom", hex(hslToArgb(bh, bs * 0.5f, Math.min(1, bl * 1.5f), 1.0f)));
        gradients.add("button", btnGrad);
        JsonObject taskGrad = new JsonObject();
        taskGrad.addProperty("top", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.4f, bl * 1.0f, 1.0f), defTbAlpha)));
        taskGrad.addProperty("bottom", hex(ColorScheme.withAlpha(hslToArgb(bh, bs * 0.4f, bl * 0.6f, 1.0f), defTbAlpha)));
        gradients.add("taskbar", taskGrad);
        root.add("gradients", gradients);

        return new JsonTheme(root);
    }

    // ── HSL ↔ RGB conversion utilities ──

    public static float[] rgbToHsl(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        float h = 0, s = 0;

        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) h = ((g - b) / d + (g < b ? 6 : 0)) / 6f;
            else if (max == g) h = ((b - r) / d + 2) / 6f;
            else h = ((r - g) / d + 4) / 6f;
        }
        return new float[]{h, s, l};
    }

    public static int hslToArgb(float h, float s, float l, float a) {
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        a = Math.max(0, Math.min(1, a));

        float r, g, b;
        if (s == 0) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hueToRgb(p, q, h + 1f/3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f/3f);
        }
        int ai = (int)(a * 255) & 0xFF;
        int ri = (int)(r * 255) & 0xFF;
        int gi = (int)(g * 255) & 0xFF;
        int bi = (int)(b * 255) & 0xFF;
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f/6f) return p + (q - p) * 6 * t;
        if (t < 1f/2f) return q;
        if (t < 2f/3f) return p + (q - p) * (2f/3f - t) * 6;
        return p;
    }

    private static String hex(int color) {
        return String.format("0x%08X", color);
    }
}
