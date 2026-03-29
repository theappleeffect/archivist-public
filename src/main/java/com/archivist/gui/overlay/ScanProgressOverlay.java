package com.archivist.gui.overlay;

import com.archivist.api.automation.SequenceHandler;
import com.archivist.config.ArchivistConfig;
import com.archivist.detection.DetectionPipeline;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A small themed HUD overlay that shows scan progress after joining a server.
 * Appears in the bottom-right corner when the mod GUI is not open.
 * Fades out like a toast once all operations complete.
 *
 * <p>When automation is running, switches to automation mode showing
 * progress (X/Y), ETA, and current server.</p>
 */
public class ScanProgressOverlay {

    private static final int FADE_OUT_DURATION = 40; // 2 seconds at 20 tps
    private static final int WIDTH = 130;
    private static final int HEIGHT = 28;
    private static final int MARGIN = 6;

    private final ArchivistConfig config;
    private final DetectionPipeline pipeline;

    // Optional automation orchestrator reference
    private SequenceHandler orchestrator;

    private boolean active = false;
    private int estimatedTicks = 0;
    private int elapsedTicks = 0;
    private int fadeOutTicks = 0;
    private boolean fading = false;

    public ScanProgressOverlay(ArchivistConfig config, DetectionPipeline pipeline) {
        this.config = config;
        this.pipeline = pipeline;
    }

    public void setOrchestrator(SequenceHandler orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Start showing the overlay with an estimated total scan time.
     * Called on server join after calculating the estimate.
     */
    public void startScan(int estimatedTotalTicks) {
        if (!isEnabled()) return;
        this.estimatedTicks = Math.max(20, estimatedTotalTicks);
        this.elapsedTicks = 0;
        this.fadeOutTicks = 0;
        this.fading = false;
        this.active = true;
    }

    /** Called every client tick to update countdown state. */
    public void tick() {
        if (!active && !isAutomationRunning()) return;
        if (!active) return; // automation mode handled in render

        if (fading) {
            fadeOutTicks++;
            if (fadeOutTicks >= FADE_OUT_DURATION) {
                active = false;
            }
            return;
        }

        elapsedTicks++;

        if (isAllDone()) {
            fading = true;
            fadeOutTicks = 0;
        }
    }

    /** Render the overlay on the HUD. */
    public void render(GuiGraphics g) {
        if (!isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // don't draw over any screen

        // Automation mode takes priority
        if (isAutomationRunning()) {
            renderAutomationMode(g, mc);
            return;
        }

        if (!active) return;
        renderScanMode(g, mc);
    }

    private void renderScanMode(GuiGraphics g, Minecraft mc) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int x = screenW - WIDTH - MARGIN;
        int y = screenH - HEIGHT - MARGIN - 40; // above hotbar area

        // Calculate alpha for fade-out
        int alpha = 255;
        if (fading) {
            alpha = Math.max(0, 255 - (255 * fadeOutTicks / FADE_OUT_DURATION));
        }
        if (alpha <= 0) return;

        ColorScheme cs = ColorScheme.get();

        // Lobby detection result
        double sessionConf = pipeline.getSessionConfidence().getConfidence();
        boolean isLobby = fading && sessionConf < 0.4;
        boolean isUncertain = fading && sessionConf >= 0.4 && sessionConf <= 0.7;

        // Apply alpha to colors
        int bg = applyAlpha(cs.tooltipBg(), alpha);
        int border = isLobby ? applyAlpha(0xFFFFAA00, alpha) : applyAlpha(cs.tooltipBorder(), alpha);
        int accent = applyAlpha(cs.accent(), alpha);
        int accentDim = applyAlpha(cs.accentDim(), alpha);
        int textPrimary = applyAlpha(cs.textPrimary(), alpha);
        int yellowText = applyAlpha(0xFFFFDD44, alpha);

        // Background + border
        RenderUtils.drawRect(g, x, y, WIDTH, HEIGHT, bg);
        RenderUtils.drawBorder(g, x, y, WIDTH, HEIGHT, border);

        // "Archivist" label (top-left)
        RenderUtils.drawText(g, "Archivist", x + 4, y + 3, accent);

        // Remaining time / confidence (top-right)
        String timeText;
        int timeColor = textPrimary;
        if (fading) {
            timeText = String.format("%.0f%%", sessionConf * 100);
            if (isLobby) timeColor = yellowText;
        } else {
            int remainingTicks = Math.max(0, estimatedTicks - elapsedTicks);
            int remainingSecs = (remainingTicks + 19) / 20;
            timeText = remainingSecs + "s";
        }
        int timeWidth = RenderUtils.scaledTextWidth(timeText);
        RenderUtils.drawText(g, timeText, x + WIDTH - timeWidth - 4, y + 3, timeColor);

        // Status label
        String statusText;
        int statusColor = textPrimary;
        if (!fading) {
            statusText = "Scanning...";
        } else if (isLobby) {
            statusText = "\u26A0 Lobby Detected";
            statusColor = yellowText;
        } else if (isUncertain) {
            statusText = "Uncertain";
        } else {
            statusText = "Server";
            statusColor = accent;
        }
        RenderUtils.drawText(g, statusText, x + 4, y + 12, statusColor);

        // Progress bar
        int barX = x + 4;
        int barY = y + HEIGHT - 6;
        int barW = WIDTH - 8;
        int barH = 3;

        float progress = Math.min(1.0f, (float) elapsedTicks / estimatedTicks);
        int filledW = (int) (barW * progress);

        RenderUtils.drawRect(g, barX, barY, barW, barH, applyAlpha(cs.scrollbarTrack(), alpha));
        if (filledW > 0) {
            int barColor = isLobby ? applyAlpha(0xFFFFAA00, alpha) : accentDim;
            RenderUtils.drawRect(g, barX, barY, filledW, barH, barColor);
        }
    }

    private void renderAutomationMode(GuiGraphics g, Minecraft mc) {
        if (orchestrator == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int x = screenW - WIDTH - MARGIN;
        int y = screenH - HEIGHT - MARGIN - 40;

        ColorScheme cs = ColorScheme.get();
        int bg = cs.tooltipBg();
        int border = cs.accent();
        int textPrimary = cs.textPrimary();
        int accent = cs.accent();
        int accentDim = cs.accentDim();

        // Background + border
        RenderUtils.drawRect(g, x, y, WIDTH, HEIGHT, bg);
        RenderUtils.drawBorder(g, x, y, WIDTH, HEIGHT, border);

        // "Automation" label (top-left)
        RenderUtils.drawText(g, "Automation", x + 4, y + 3, accent);

        // ETA (top-right)
        var autoState = orchestrator.getState();
        int completed = autoState.getCompletedCount();
        int failed = autoState.getFailedCount();
        int total = orchestrator.getCurrentServerList().size();

        String etaText = "";
        if (completed + failed > 0) {
            long elapsedMs = orchestrator.getElapsedMs();
            long avgMs = elapsedMs / (completed + failed);
            int remaining = autoState.getRemainingCount(total);
            long etaMs = avgMs * remaining;
            etaText = "~" + formatDuration(etaMs);
        } else {
            etaText = (completed + failed) + "/" + total;
        }
        int etaWidth = RenderUtils.scaledTextWidth(etaText);
        RenderUtils.drawText(g, etaText, x + WIDTH - etaWidth - 4, y + 3, textPrimary);

        // Status line: current server (truncated) or phase
        String serverName = orchestrator.getCurrentServerDisplay();
        if (serverName.length() > 20) {
            serverName = serverName.substring(0, 18) + "..";
        }
        RenderUtils.drawText(g, serverName, x + 4, y + 12, textPrimary);

        // Progress bar
        int barX = x + 4;
        int barY = y + HEIGHT - 6;
        int barW = WIDTH - 8;
        int barH = 3;

        float progress = total > 0 ? (float) (completed + failed) / total : 0f;
        int filledW = (int) (barW * progress);

        RenderUtils.drawRect(g, barX, barY, barW, barH, cs.scrollbarTrack());
        if (filledW > 0) {
            RenderUtils.drawRect(g, barX, barY, filledW, barH, accentDim);
        }
    }

    private boolean isAutomationRunning() {
        if (orchestrator == null) return false;
        var phase = orchestrator.getPhase();
        return phase != SequenceHandler.Phase.IDLE
                && phase != SequenceHandler.Phase.COMPLETED;
    }

    /** Reset on disconnect. */
    public void reset() {
        active = false;
        fading = false;
        elapsedTicks = 0;
        fadeOutTicks = 0;
    }

    private boolean isEnabled() {
        return config.showScanOverlay;
    }

    private boolean isAllDone() {
        if (elapsedTicks < 40) return false;
        return pipeline.isScanComplete();
    }

    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long mins = (totalSec % 3600) / 60;
        if (hours > 0) return hours + "h" + mins + "m";
        if (mins > 0) return mins + "m";
        return totalSec + "s";
    }

    private static int applyAlpha(int color, int alpha) {
        int existingAlpha = (color >>> 24) & 0xFF;
        int newAlpha = (existingAlpha * alpha) / 255;
        return (color & 0x00FFFFFF) | (newAlpha << 24);
    }
}
