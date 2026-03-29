package com.archivist.gui.overlay;

import com.archivist.api.ApiGuiHelper;
import com.archivist.api.DeviceCodeFlow;
import com.archivist.config.ArchivistConfig;
import com.archivist.config.ConfigManager;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.net.URI;
import java.util.Base64;

/**
 * Post-tutorial overlay that asks the user if they want to auto-submit
 * plugin data to archivist-web.net, and handles the device code flow.
 *
 * <p>Three visual phases: QUESTION → DEVICE_CODE → RESULT</p>
 */
public class ArchivistWebPromptOverlay {

    private static final int BOX_WIDTH = 260;
    private static final int PADDING = 10;
    private static final int BUTTON_W = 60;
    private static final int BUTTON_H = 14;

    private enum Phase { QUESTION, DEVICE_CODE, RESULT }

    private boolean active = true;
    private Phase phase = Phase.QUESTION;
    private long tickCount = 0;

    private final ArchivistConfig config;
    private DeviceCodeFlow flow;
    private boolean resultSuccess = false;
    private String resultMessage = "";

    // Cached button positions from render
    private int cachedYesX, cachedYesY;
    private int cachedNoX, cachedNoY;
    private int cachedOpenBrowserX, cachedOpenBrowserY, cachedOpenBrowserW;
    private int cachedCancelX, cachedCancelY;
    private int cachedCloseX, cachedCloseY;
    private int cachedRetryX, cachedRetryY;
    private boolean cachedHasRetry;

    public ArchivistWebPromptOverlay(ArchivistConfig config) {
        this.config = config;
    }

    public boolean isActive() { return active; }

    public void tick() { tickCount++; }

    // ── Rendering ──

    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!active) return;
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        ColorScheme cs = ColorScheme.get();

        // Dark scrim
        g.fill(0, 0, screenW, screenH, cs.screenOverlay());

        switch (phase) {
            case QUESTION -> renderQuestion(g, mouseX, mouseY, screenW, screenH, cs);
            case DEVICE_CODE -> renderDeviceCode(g, mouseX, mouseY, screenW, screenH, cs);
            case RESULT -> renderResult(g, mouseX, mouseY, screenW, screenH, cs);
        }
    }

    private void renderQuestion(GuiGraphics g, int mouseX, int mouseY, int screenW, int screenH, ColorScheme cs) {
        String[] lines = {
                "Would you like to automatically",
                "submit plugins to archivist-web.net?",
                "",
                "This helps build a public database of",
                "server plugins. You can change this",
                "later in Settings > API."
        };

        int textH = lines.length * 10;
        int boxH = PADDING * 2 + textH + 8 + BUTTON_H;
        int boxX = (screenW - BOX_WIDTH) / 2;
        int boxY = (screenH - boxH) / 2;

        RenderUtils.drawRect(g, boxX, boxY, BOX_WIDTH, boxH, cs.tooltipBg());
        RenderUtils.drawBorder(g, boxX, boxY, BOX_WIDTH, boxH, cs.accent());

        int ty = boxY + PADDING;
        for (String line : lines) {
            RenderUtils.drawText(g, line, boxX + PADDING, ty, cs.textPrimary());
            ty += 10;
        }
        ty += 8;

        // "No" button (left)
        cachedNoX = boxX + PADDING;
        cachedNoY = ty;
        drawButton(g, "No", cachedNoX, cachedNoY, BUTTON_W, BUTTON_H,
                mouseX, mouseY, cs.textSecondary(), cs.button(), cs.buttonHover());

        // "Yes" button (right)
        cachedYesX = boxX + BOX_WIDTH - PADDING - BUTTON_W;
        cachedYesY = ty;
        drawButton(g, "Yes", cachedYesX, cachedYesY, BUTTON_W, BUTTON_H,
                mouseX, mouseY, cs.textPrimary(), cs.accentDim(), cs.accent());
    }

    private void renderDeviceCode(GuiGraphics g, int mouseX, int mouseY, int screenW, int screenH, ColorScheme cs) {
        String code = flow != null && flow.getUserCode() != null ? flow.getUserCode() : "...";
        String url = flow != null && flow.getVerificationUrl() != null ? flow.getVerificationUrl() : "archivist-web.net/link";

        String[] lines = {
                "Go to:",
                url,
                "",
                "Enter code:"
        };

        int textH = lines.length * 10;
        int codeLineH = 14; // larger text for code
        // Pulsing "Waiting..." text
        float pulse = (float) (0.5 + 0.5 * Math.sin(tickCount * 0.1));
        int waitAlpha = (int) (100 + 155 * pulse);

        int boxH = PADDING * 2 + textH + codeLineH + 6 + 10 + 10 + BUTTON_H + 4 + BUTTON_H;
        int boxX = (screenW - BOX_WIDTH) / 2;
        int boxY = (screenH - boxH) / 2;

        RenderUtils.drawRect(g, boxX, boxY, BOX_WIDTH, boxH, cs.tooltipBg());
        RenderUtils.drawBorder(g, boxX, boxY, BOX_WIDTH, boxH, cs.accent());

        int ty = boxY + PADDING;

        // "Go to:" label
        RenderUtils.drawText(g, lines[0], boxX + PADDING, ty, cs.textSecondary());
        ty += 10;

        // URL (accent color)
        RenderUtils.drawText(g, lines[1], boxX + PADDING, ty, cs.accent());
        ty += 10;
        ty += 10; // blank line

        // "Enter code:" label
        RenderUtils.drawText(g, lines[3], boxX + PADDING, ty, cs.textSecondary());
        ty += 10;

        // Code displayed prominently
        int codeW = RenderUtils.scaledTextWidth(code);
        int codeX = boxX + (BOX_WIDTH - codeW) / 2;
        RenderUtils.drawText(g, code, codeX, ty, cs.accent());
        ty += codeLineH + 6;

        // "Waiting for authorization..."
        int waitColor = ColorScheme.withAlpha(cs.textSecondary(), waitAlpha);
        RenderUtils.drawText(g, "Waiting for authorization...", boxX + PADDING, ty, waitColor);
        ty += 10 + 4;

        // "Open Browser" button (left)
        cachedOpenBrowserW = 80;
        cachedOpenBrowserX = boxX + PADDING;
        cachedOpenBrowserY = ty;
        drawButton(g, "Open Browser", cachedOpenBrowserX, cachedOpenBrowserY, cachedOpenBrowserW, BUTTON_H,
                mouseX, mouseY, cs.textPrimary(), cs.accentDim(), cs.accent());

        // "Cancel" button (right)
        cachedCancelX = boxX + BOX_WIDTH - PADDING - BUTTON_W;
        cachedCancelY = ty;
        drawButton(g, "Cancel", cachedCancelX, cachedCancelY, BUTTON_W, BUTTON_H,
                mouseX, mouseY, cs.textSecondary(), cs.button(), cs.buttonHover());
    }

    private void renderResult(GuiGraphics g, int mouseX, int mouseY, int screenW, int screenH, ColorScheme cs) {
        String[] lines = resultMessage.split("\n");
        int textH = lines.length * 10;
        cachedHasRetry = !resultSuccess;

        int buttonsH = cachedHasRetry ? BUTTON_H + 4 + BUTTON_H : BUTTON_H;
        int boxH = PADDING * 2 + textH + 8 + buttonsH;
        int boxX = (screenW - BOX_WIDTH) / 2;
        int boxY = (screenH - boxH) / 2;

        RenderUtils.drawRect(g, boxX, boxY, BOX_WIDTH, boxH, cs.tooltipBg());
        RenderUtils.drawBorder(g, boxX, boxY, BOX_WIDTH, boxH,
                resultSuccess ? 0xFF44BB44 : 0xFFBB4444);

        int ty = boxY + PADDING;
        int msgColor = resultSuccess ? 0xFF44BB44 : 0xFFBB4444;
        for (String line : lines) {
            RenderUtils.drawText(g, line, boxX + PADDING, ty, msgColor);
            ty += 10;
        }
        ty += 8;

        // "Close" button
        cachedCloseX = boxX + BOX_WIDTH - PADDING - BUTTON_W;
        cachedCloseY = ty;
        drawButton(g, "Close", cachedCloseX, cachedCloseY, BUTTON_W, BUTTON_H,
                mouseX, mouseY, cs.textPrimary(), cs.accentDim(), cs.accent());

        if (cachedHasRetry) {
            // "Try Again" button
            cachedRetryX = boxX + PADDING;
            cachedRetryY = ty;
            drawButton(g, "Try Again", cachedRetryX, cachedRetryY, 70, BUTTON_H,
                    mouseX, mouseY, cs.textSecondary(), cs.button(), cs.buttonHover());
        }
    }

    private void drawButton(GuiGraphics g, String label, int x, int y, int w, int h,
                             int mouseX, int mouseY, int textColor, int bgColor, int hoverColor) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        RenderUtils.drawRect(g, x, y, w, h, hovered ? hoverColor : bgColor);
        RenderUtils.drawBorder(g, x, y, w, h, hovered ? ColorScheme.get().accent() : ColorScheme.get().windowBorder());
        int textW = RenderUtils.scaledTextWidth(label);
        RenderUtils.drawText(g, label, x + (w - textW) / 2, y + (h - 8) / 2, textColor);
    }

    // ── Input ──

    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!active) return false;

        switch (phase) {
            case QUESTION -> {
                if (isOver(mouseX, mouseY, cachedYesX, cachedYesY, BUTTON_W, BUTTON_H)) {
                    onYes();
                    return true;
                }
                if (isOver(mouseX, mouseY, cachedNoX, cachedNoY, BUTTON_W, BUTTON_H)) {
                    onNo();
                    return true;
                }
            }
            case DEVICE_CODE -> {
                if (isOver(mouseX, mouseY, cachedOpenBrowserX, cachedOpenBrowserY, cachedOpenBrowserW, BUTTON_H)) {
                    openBrowser();
                    return true;
                }
                if (isOver(mouseX, mouseY, cachedCancelX, cachedCancelY, BUTTON_W, BUTTON_H)) {
                    onCancel();
                    return true;
                }
            }
            case RESULT -> {
                if (isOver(mouseX, mouseY, cachedCloseX, cachedCloseY, BUTTON_W, BUTTON_H)) {
                    close();
                    return true;
                }
                if (cachedHasRetry && isOver(mouseX, mouseY, cachedRetryX, cachedRetryY, 70, BUTTON_H)) {
                    onRetry();
                    return true;
                }
            }
        }

        return true; // consume all clicks
    }

    public boolean onKeyPressed(int keyCode) {
        if (!active) return false;

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            switch (phase) {
                case QUESTION -> onNo();
                case DEVICE_CODE -> onCancel();
                case RESULT -> close();
            }
            return true;
        }

        return true; // consume all keys
    }

    // ── Actions ──

    private void onYes() {
        phase = Phase.DEVICE_CODE;
        flow = new DeviceCodeFlow(this::onFlowStateChanged);
        flow.start();
    }

    private void onNo() {
        config.archivistWebPromptShown = true;
        ConfigManager.save(config);
        active = false;
    }

    private void onCancel() {
        if (flow != null) flow.cancel();
        config.archivistWebPromptShown = true;
        ConfigManager.save(config);
        active = false;
    }

    private void onRetry() {
        phase = Phase.DEVICE_CODE;
        flow = new DeviceCodeFlow(this::onFlowStateChanged);
        flow.start();
    }

    private void close() {
        active = false;
    }

    private void openBrowser() {
        String url = flow != null && flow.getVerificationUrl() != null
                ? flow.getVerificationUrl() : "https://archivist-web.net/link";
        String userCode = flow != null ? flow.getUserCode() : null;
        if (userCode != null) url += "?code=" + userCode;
        ApiGuiHelper.openUrl(url);
    }

    private void onFlowStateChanged(DeviceCodeFlow.State newState) {
        switch (newState) {
            case COMPLETED -> {
                String key = flow.getApiKey();
                if (key != null && !key.isEmpty()) {
                    config.archivistWebApiKeyEncoded = Base64.getEncoder().encodeToString(key.getBytes());
                    config.archivistWebEnabled = true;
                    config.archivistWebPromptShown = true;
                    ConfigManager.save(config);
                    resultSuccess = true;
                    resultMessage = "Connected!\nYour API key has been saved.";
                } else {
                    resultSuccess = false;
                    resultMessage = "Received empty API key.\nPlease try again.";
                }
                phase = Phase.RESULT;
            }
            case EXPIRED -> {
                config.archivistWebPromptShown = true;
                ConfigManager.save(config);
                resultSuccess = false;
                resultMessage = "Code expired.\nPlease try again.";
                phase = Phase.RESULT;
            }
            case ERROR -> {
                String err = flow.getErrorMessage();
                resultSuccess = false;
                resultMessage = "Error: " + (err != null ? err : "Unknown error");
                phase = Phase.RESULT;
            }
            default -> {} // REQUESTING, AWAITING_USER, POLLING — no phase change
        }
    }

    private static boolean isOver(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
