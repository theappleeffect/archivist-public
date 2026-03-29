package com.archivist.gui.render;

import com.archivist.gui.widgets.TextSelectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Static drawing helpers used by all widgets.
 */
public final class RenderUtils {

    private RenderUtils() {}

    public static final float TEXT_SCALE = 0.75f;

    // ── Rectangle Drawing ───────────────────────────────────────────────────

    public static void drawRect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, color);
    }

    public static void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);           // top
        g.fill(x, y + h - 1, x + w, y + h, color);   // bottom
        g.fill(x, y + 1, x + 1, y + h - 1, color);   // left
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color); // right
    }

    public static void drawHLine(GuiGraphics g, int x, int y, int w, int color) {
        g.fill(x, y, x + w, y + 1, color);
    }

    public static void drawVLine(GuiGraphics g, int x, int y, int h, int color) {
        g.fill(x, y, x + 1, y + h, color);
    }

    /**
     * Draw a diagonal gradient rectangle (45°).
     * brightColor (topColor) in the top-right corner, darkColor (bottomColor) in the bottom-left corner.
     * All gradients in the mod use this diagonal style.
     */
    public static void drawGradientRect(GuiGraphics g, int x, int y, int w, int h, int brightColor, int darkColor) {
        if (w <= 0 || h <= 0) return;
        int midColor = lerpColor(darkColor, brightColor, 0.5f);
        int stripW = 2;
        for (int sx = 0; sx < w; sx += stripW) {
            float t = (float) sx / Math.max(w - 1, 1);
            int colTop = lerpColor(midColor, brightColor, t);
            int colBottom = lerpColor(darkColor, midColor, t);
            int sw = Math.min(stripW, w - sx);
            g.fillGradient(x + sx, y, x + sx + sw, y + h, colTop, colBottom);
        }
    }

    /** Linearly interpolate between two ARGB colors. */
    public static int lerpColor(int from, int to, float t) {
        int a1 = (from >>> 24) & 0xFF, r1 = (from >>> 16) & 0xFF, g1 = (from >>> 8) & 0xFF, b1 = from & 0xFF;
        int a2 = (to >>> 24) & 0xFF, r2 = (to >>> 16) & 0xFF, g2 = (to >>> 8) & 0xFF, b2 = to & 0xFF;
        int a = a1 + (int)((a2 - a1) * t);
        int r = r1 + (int)((r2 - r1) * t);
        int g = g1 + (int)((g2 - g1) * t);
        int b = b1 + (int)((b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Text Drawing ────────────────────────────────────────────────────────

    /** Draw text at TEXT_SCALE with shadow. Coordinates are in unscaled screen space. */
    public static void drawText(GuiGraphics g, String text, int x, int y, int color) {
        drawScaledText(g, text, x, y, color, true);
    }

    /** Draw text at TEXT_SCALE without shadow. */
    public static void drawTextNoShadow(GuiGraphics g, String text, int x, int y, int color) {
        drawScaledText(g, text, x, y, color, false);
    }

    /** Draw text at the configured TEXT_SCALE. */
    public static void drawScaledText(GuiGraphics g, String text, int x, int y, int color, boolean shadow) {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        var font = mc.font;
        float s = TEXT_SCALE;
        var pose = g.pose();
        //? if >=1.21.9 {
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(s, s);
        //?} else {
        /*pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(s, s, 1);*/
        //?}
        TextSelectionManager.checkAndHighlight(g, text, x, y, s);
        g.drawString(font, text, 0, 0, color, shadow);
        //? if >=1.21.9
        pose.popMatrix();
        //? if <1.21.9
        /*pose.popPose();*/
    }

    /** Draw text at a custom scale. */
    public static void drawTextAtScale(GuiGraphics g, String text, int x, int y, int color, float scale) {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        var font = mc.font;
        var pose = g.pose();
        //? if >=1.21.9 {
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        //?} else {
        /*pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);*/
        //?}
        TextSelectionManager.checkAndHighlight(g, text, x, y, scale);
        g.drawString(font, text, 0, 0, color, true);
        //? if >=1.21.9
        pose.popMatrix();
        //? if <1.21.9
        /*pose.popPose();*/
    }

    /** Effective line height after scaling. */
    public static int scaledFontHeight() {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return 0;
        return (int) (mc.font.lineHeight * TEXT_SCALE);
    }

    /** Effective string width after scaling. */
    public static int scaledTextWidth(String text) {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return 0;
        return (int) (mc.font.width(text) * TEXT_SCALE);
    }

    /** Trim text to fit within maxWidth, adding "..." if truncated. */
    public static String trimToWidth(String text, int maxWidth) {
        if (scaledTextWidth(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellipsisW = scaledTextWidth(ellipsis);
        if (maxWidth <= ellipsisW) return ellipsis;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            if (scaledTextWidth(sb.toString()) + ellipsisW > maxWidth) {
                sb.deleteCharAt(sb.length() - 1);
                sb.append(ellipsis);
                return sb.toString();
            }
        }
        return text;
    }

    // ── Highlighted Text ─────────────────────────────────────────────────────

    /** Draw text with matching substring highlighted in a different color. */
    public static void drawHighlightedText(GuiGraphics g, String text, String query, int x, int y,
                                           int normalColor, int highlightColor, int maxWidth) {
        if (query == null || query.isEmpty()) {
            drawText(g, trimToWidth(text, maxWidth), x, y, normalColor);
            return;
        }
        String lower = text.toLowerCase();
        String queryLower = query.toLowerCase();
        int currentX = x;
        int lastEnd = 0;

        while (lastEnd < text.length()) {
            int matchIdx = lower.indexOf(queryLower, lastEnd);
            if (matchIdx < 0) {
                String remaining = text.substring(lastEnd);
                if (currentX - x + scaledTextWidth(remaining) > maxWidth) {
                    remaining = trimToWidth(remaining, maxWidth - (currentX - x));
                }
                drawText(g, remaining, currentX, y, normalColor);
                break;
            }

            if (matchIdx > lastEnd) {
                String before = text.substring(lastEnd, matchIdx);
                if (currentX - x + scaledTextWidth(before) > maxWidth) {
                    before = trimToWidth(before, maxWidth - (currentX - x));
                    drawText(g, before, currentX, y, normalColor);
                    break;
                }
                drawText(g, before, currentX, y, normalColor);
                currentX += scaledTextWidth(before);
            }

            String match = text.substring(matchIdx, matchIdx + query.length());
            if (currentX - x + scaledTextWidth(match) > maxWidth) {
                match = trimToWidth(match, maxWidth - (currentX - x));
                drawText(g, match, currentX, y, highlightColor);
                break;
            }
            drawText(g, match, currentX, y, highlightColor);
            currentX += scaledTextWidth(match);

            lastEnd = matchIdx + query.length();
        }
    }

    // ── Text Wrapping ────────────────────────────────────────────────────────

    /** Split text into lines that each fit within maxWidth (word-breaking). */
    public static List<String> wrapText(String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            lines.add(text != null ? text : "");
            return lines;
        }

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.isEmpty()) {
                if (scaledTextWidth(word) > maxWidth) {
                    for (int i = 0; i < word.length(); i++) {
                        String test = currentLine.toString() + word.charAt(i);
                        if (scaledTextWidth(test) > maxWidth && !currentLine.isEmpty()) {
                            lines.add(currentLine.toString());
                            currentLine = new StringBuilder();
                        }
                        currentLine.append(word.charAt(i));
                    }
                } else {
                    currentLine.append(word);
                }
            } else {
                String test = currentLine + " " + word;
                if (scaledTextWidth(test) > maxWidth) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder();
                    if (scaledTextWidth(word) > maxWidth) {
                        for (int i = 0; i < word.length(); i++) {
                            String charTest = currentLine.toString() + word.charAt(i);
                            if (scaledTextWidth(charTest) > maxWidth && !currentLine.isEmpty()) {
                                lines.add(currentLine.toString());
                                currentLine = new StringBuilder();
                            }
                            currentLine.append(word.charAt(i));
                        }
                    } else {
                        currentLine.append(word);
                    }
                } else {
                    currentLine.append(" ").append(word);
                }
            }
        }

        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }

        if (lines.isEmpty()) {
            lines.add("");
        }

        return lines;
    }

    /** Draw text with word wrapping. Returns the total height used. */
    public static int drawWrappedText(GuiGraphics g, String text, int x, int y, int maxWidth, int color) {
        List<String> lines = wrapText(text, maxWidth);
        int lineH = scaledFontHeight();
        for (int i = 0; i < lines.size(); i++) {
            drawText(g, lines.get(i), x, y + i * lineH, color);
        }
        return lines.size() * lineH;
    }

    /** Calculate the total height wrapped text would occupy. */
    public static int wrappedTextHeight(String text, int maxWidth) {
        return wrapText(text, maxWidth).size() * scaledFontHeight();
    }

    // ── Scissor ─────────────────────────────────────────────────────────────

    public static void enableScissor(GuiGraphics g, int x, int y, int w, int h) {
        g.enableScissor(x, y, x + w, y + h);
    }

    public static void disableScissor(GuiGraphics g) {
        g.disableScissor();
    }
}
