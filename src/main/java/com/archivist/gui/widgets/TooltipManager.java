package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Manages tooltip rendering with a hover delay.
 * Uses a beginFrame() pattern to avoid per-widget clear() race conditions.
 */
public class TooltipManager {

    private static String currentFrameText = null;
    private static double currentFrameX, currentFrameY;

    private static String activeText = null;
    private static double activeX, activeY;
    private static long hoverStartTime = 0;
    private static final long DELAY_MS = 500;

    public static void beginFrame() {
        if (currentFrameText != null) {
            if (!currentFrameText.equals(activeText)) {
                activeText = currentFrameText;
                activeX = currentFrameX;
                activeY = currentFrameY;
                hoverStartTime = System.currentTimeMillis();
            }
        } else {
            activeText = null;
        }
        currentFrameText = null;
    }

    public static void offer(String fullText, double x, double y) {
        currentFrameText = fullText;
        currentFrameX = x;
        currentFrameY = y;
    }

    public static void render(GuiGraphics g) {
        if (activeText == null) return;
        if (System.currentTimeMillis() - hoverStartTime < DELAY_MS) return;

        ColorScheme cs = ColorScheme.get();
        int textW = RenderUtils.scaledTextWidth(activeText);
        int textH = RenderUtils.scaledFontHeight();
        int pad = 3;

        double tipX = activeX;
        double tipY = activeY - textH - 6;

        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        if (tipY < 0) tipY = activeY + 14;
        if (tipX + textW + pad * 2 > screenW) tipX = screenW - textW - pad * 2;
        if (tipX < 0) tipX = 0;

        int ix = (int) tipX;
        int iy = (int) tipY;

        RenderUtils.drawRect(g, ix - pad, iy - 2, textW + pad * 2, textH + 4, cs.tooltipBg());
        RenderUtils.drawBorder(g, ix - pad, iy - 2, textW + pad * 2, textH + 4, cs.tooltipBorder());
        RenderUtils.drawText(g, activeText, ix, iy, cs.textPrimary());
    }
}
