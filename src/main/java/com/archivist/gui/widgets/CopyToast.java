package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class CopyToast {

    public enum ToastType { COPY, LOBBY }

    private static String message = null;
    private static ToastType toastType = ToastType.COPY;
    private static float animProgress = 0f;
    private static float holdTimer = 0f;

    private static final float FADE_IN_SPEED = 0.2f;
    private static final float FADE_OUT_SPEED = 0.1f;
    private static final float HOLD_DURATION = 90f;
    private static final float LOBBY_HOLD_DURATION = 180f;
    private static final int SLIDE_OFFSET = 4;

    private static float glowPhase = 0f;

    private enum State { HIDDEN, FADE_IN, HOLD, FADE_OUT }
    private static State state = State.HIDDEN;

    public static void show(String text) {
        message = "Copied to clipboard";
        toastType = ToastType.COPY;
        state = State.FADE_IN;
        animProgress = 0f;
        holdTimer = 0f;
    }

    public static void showLobby() {
        message = "\u26A0 Lobby Detected";
        toastType = ToastType.LOBBY;
        state = State.FADE_IN;
        animProgress = 0f;
        holdTimer = 0f;
        glowPhase = 0f;
    }

    public static void render(GuiGraphics g) {
        if (state == State.HIDDEN || message == null) return;

        float holdDuration = toastType == ToastType.LOBBY ? LOBBY_HOLD_DURATION : HOLD_DURATION;

        switch (state) {
            case FADE_IN -> {
                animProgress += FADE_IN_SPEED;
                if (animProgress >= 1f) {
                    animProgress = 1f;
                    state = State.HOLD;
                }
            }
            case HOLD -> {
                holdTimer += 1f;
                if (holdTimer >= holdDuration) {
                    state = State.FADE_OUT;
                }
            }
            case FADE_OUT -> {
                animProgress -= FADE_OUT_SPEED;
                if (animProgress <= 0f) {
                    animProgress = 0f;
                    state = State.HIDDEN;
                    message = null;
                    return;
                }
            }
            default -> {}
        }

        if (toastType == ToastType.LOBBY) {
            glowPhase += 0.08f;
        }

        ColorScheme cs = ColorScheme.get();
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        int textW = RenderUtils.scaledTextWidth(message);
        int textH = RenderUtils.scaledFontHeight();
        int pad = 5;
        int boxW = textW + pad * 2;
        int boxH = textH + pad * 2;

        int boxX = (screenW - boxW) / 2;
        int baseY = screenH - Taskbar.TASKBAR_HEIGHT - boxH - 6;
        int boxY = baseY + (int) ((1f - animProgress) * SLIDE_OFFSET);

        int alpha = (int) (animProgress * 255);

        if (toastType == ToastType.LOBBY) {
            float glow = 0.5f + 0.5f * (float) Math.sin(glowPhase);
            int glowAlpha = (int) (alpha * 0.3f * glow);
            int yellowGlow = (glowAlpha << 24) | (ColorScheme.withAlpha(cs.accent(), 0x00) & 0x00FFFFFF);
            RenderUtils.drawRect(g, boxX - 2, boxY - 2, boxW + 4, boxH + 4, yellowGlow);

            int bgColor = ColorScheme.withAlpha(cs.tooltipBg(), alpha);
            int borderColor = ColorScheme.withAlpha(cs.accent(), alpha);
            int textColor = ColorScheme.withAlpha(cs.textPrimary(), alpha);

            RenderUtils.drawRect(g, boxX, boxY, boxW, boxH, bgColor);
            RenderUtils.drawBorder(g, boxX, boxY, boxW, boxH, borderColor);
            RenderUtils.drawText(g, message, boxX + pad, boxY + pad, textColor);
        } else {
            int bgColor = ColorScheme.withAlpha(cs.tooltipBg(), alpha);
            int borderColor = ColorScheme.withAlpha(cs.accent(), alpha);
            int textColor = ColorScheme.withAlpha(cs.textPrimary(), alpha);

            RenderUtils.drawRect(g, boxX, boxY, boxW, boxH, bgColor);
            RenderUtils.drawBorder(g, boxX, boxY, boxW, boxH, borderColor);
            RenderUtils.drawText(g, message, boxX + pad, boxY + pad, textColor);
        }
    }
}
