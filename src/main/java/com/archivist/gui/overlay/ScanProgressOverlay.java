package com.archivist.gui.overlay;

import com.archivist.api.automation.SequenceHandler;
import com.archivist.config.ArchivistConfig;
import com.archivist.detection.DetectionPipeline;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class ScanProgressOverlay {

    private static final int WIDTH = 130;
    private static final int HEIGHT = 28;
    private static final int MARGIN = 6;

    private static final float SLIDE_IN_SPEED = 0.12f;
    private static final float SLIDE_OUT_SPEED = 0.08f;
    private static final float HOLD_DURATION = 90f;

    private final ArchivistConfig config;
    private final DetectionPipeline pipeline;

    private SequenceHandler orchestrator;

    private enum OverlayState { HIDDEN, PENDING, SLIDE_IN, HOLD, SLIDE_OUT }
    private OverlayState overlayState = OverlayState.HIDDEN;

    private int estimatedTicks = 0;
    private int elapsedTicks = 0;
    private float slideProgress = 0f;
    private float holdTimer = 0f;

    private java.util.function.Supplier<String> domainSupplier;
    private String cachedResult = "";
    private int cachedResultColor = 0xFFFFFFFF;
    private int cachedBorderColor = 0xFFFFFFFF;
    private boolean showConfidence = true;

    public ScanProgressOverlay(ArchivistConfig config, DetectionPipeline pipeline) {
        this.config = config;
        this.pipeline = pipeline;
    }

    public void setOrchestrator(SequenceHandler orchestrator) {
        this.orchestrator = orchestrator;
    }

    public void setDomainSupplier(java.util.function.Supplier<String> supplier) {
        this.domainSupplier = supplier;
    }

    public void startScan(int estimatedTotalTicks) {
        if (!isEnabled()) return;
        this.estimatedTicks = Math.max(20, estimatedTotalTicks);
        this.elapsedTicks = 0;
        this.holdTimer = 0;
        this.slideProgress = 0f;

        String domain = domainSupplier != null ? domainSupplier.get() : "unknown";
        if (config.isExcluded(domain)) {
            cachedResult = "\u26D4 Excluded";
            cachedResultColor = 0xFFFF4444;
            cachedBorderColor = 0xFFFF4444;
            showConfidence = false;
            overlayState = OverlayState.SLIDE_IN;
        } else if (config.isException(domain)) {
            cachedResult = "\u26A0 Exception";
            cachedResultColor = 0xFFFFAA00;
            cachedBorderColor = 0xFFFFAA00;
            showConfidence = false;
            overlayState = OverlayState.SLIDE_IN;
        } else {
            cachedResult = "Thinking...";
            cachedResultColor = 0xFF88DDDD;
            cachedBorderColor = ColorScheme.get().tooltipBorder();
            showConfidence = true;
            overlayState = OverlayState.SLIDE_IN;
        }
    }

    public void tick() {
        if (overlayState == OverlayState.HIDDEN) return;
        if (showConfidence && cachedResult.equals("Thinking...")) {
            elapsedTicks++;
            if (isAllDone()) {
                determineResult();
                holdTimer = 0f;
                if (overlayState == OverlayState.HOLD) {
                }
            }
        }
    }

    private void tickAnimation() {
        switch (overlayState) {
            case SLIDE_IN -> {
                slideProgress += SLIDE_IN_SPEED;
                if (slideProgress >= 1f) {
                    slideProgress = 1f;
                    overlayState = OverlayState.HOLD;
                    holdTimer = 0f;
                }
            }
            case HOLD -> {
                if (cachedResult.equals("Thinking...")) {
                } else {
                    holdTimer += 1f;
                    if (holdTimer >= HOLD_DURATION) {
                        overlayState = OverlayState.SLIDE_OUT;
                    }
                }
            }
            case SLIDE_OUT -> {
                slideProgress -= SLIDE_OUT_SPEED;
                if (slideProgress <= 0f) {
                    slideProgress = 0f;
                    overlayState = OverlayState.HIDDEN;
                }
            }
            default -> {}
        }
    }

    private void determineResult() {
        double sessionConf = pipeline.getSessionConfidence().getConfidence();
        boolean isLobby = sessionConf < 0.4;
        boolean isUncertain = sessionConf >= 0.4 && sessionConf <= 0.7;

        ColorScheme cs = ColorScheme.get();

        if (isLobby) {
            cachedResult = "You are in a Lobby";
            cachedResultColor = 0xFFFFDD44;
            cachedBorderColor = 0xFFFFAA00;
        } else if (isUncertain) {
            cachedResult = "Uncertain";
            cachedResultColor = cs.textPrimary();
            cachedBorderColor = cs.tooltipBorder();
        } else {
            cachedResult = "You are in a Server";
            cachedResultColor = cs.accent();
            cachedBorderColor = cs.accent();
        }
    }

    public void render(GuiGraphics g) {
        if (!isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        if (isAutomationRunning()) {
            renderAutomationMode(g, mc);
            return;
        }

        if (overlayState == OverlayState.HIDDEN) return;
        tickAnimation();
        renderScanResult(g, mc);
    }

    private void renderScanResult(GuiGraphics g, Minecraft mc) {
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int slideOffset = (int) ((1f - slideProgress) * (WIDTH + MARGIN));
        int x = screenW - WIDTH - MARGIN + slideOffset;
        int y = screenH - HEIGHT - MARGIN - 40;

        int alpha = (int) (slideProgress * 255);
        if (alpha <= 0) return;

        ColorScheme cs = ColorScheme.get();

        int bg = applyAlpha(cs.tooltipBg(), alpha);
        int border = applyAlpha(cachedBorderColor, alpha);
        int accent = applyAlpha(cs.accent(), alpha);
        int textPrimary = applyAlpha(cachedResultColor, alpha);

        RenderUtils.drawRect(g, x, y, WIDTH, HEIGHT, bg);
        RenderUtils.drawBorder(g, x, y, WIDTH, HEIGHT, border);

        RenderUtils.drawText(g, "Archivist", x + 4, y + 3, applyAlpha(cs.accent(), alpha));

        if (showConfidence) {
            double sessionConf = pipeline.getSessionConfidence().getConfidence();
            String confText = String.format("%.0f%%", sessionConf * 100);
            int confWidth = RenderUtils.scaledTextWidth(confText);
            RenderUtils.drawText(g, confText, x + WIDTH - confWidth - 4, y + 3, applyAlpha(cs.textPrimary(), alpha));
        }

        RenderUtils.drawText(g, cachedResult, x + 4, y + 12, textPrimary);
    }

    private void renderAutomationMode(GuiGraphics g, Minecraft mc) {
        if (orchestrator == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int x = screenW - WIDTH - MARGIN;
        int y = screenH - HEIGHT - MARGIN - 40;

        ColorScheme cs = ColorScheme.get();
        var autoPhase = orchestrator.getPhase();
        var autoState = orchestrator.getState();
        int completed = autoState.getCompletedCount();
        int failed = autoState.getFailedCount();
        int total = orchestrator.getCurrentServerList().size();

        String domain = domainSupplier != null ? domainSupplier.get() : "unknown";
        boolean isExcluded = config.isExcluded(domain);
        boolean isException = config.isException(domain);

        double sessionConf = pipeline.getSessionConfidence().getConfidence();
        boolean isLobby = autoPhase == SequenceHandler.Phase.SCANNING && sessionConf < 0.4;

        int border;
        String statusText;
        int statusColor;

        if (isExcluded) {
            border = 0xFFFF4444;
            statusText = "Excluded \u2014 skipping";
            statusColor = 0xFFFF4444;
        } else if (isException) {
            border = 0xFFFFAA00;
            statusText = "Exception";
            statusColor = 0xFFFFAA00;
        } else {
            switch (autoPhase) {
                case CONNECTING -> {
                    border = cs.accent();
                    statusText = "Connecting...";
                    statusColor = cs.textSecondary();
                }
                case SCANNING -> {
                    if (isLobby) {
                        border = 0xFFFFAA00;
                        statusText = "Lobby \u2014 searching...";
                        statusColor = 0xFFFFDD44;
                    } else if (pipeline.isScanComplete()) {
                        border = cs.accent();
                        statusText = "Server \u2014 logging";
                        statusColor = cs.accent();
                    } else {
                        border = cs.accent();
                        statusText = "Thinking...";
                        statusColor = 0xFF88DDDD;
                    }
                }
                case DISCONNECTING -> {
                    border = cs.accent();
                    statusText = "Disconnecting...";
                    statusColor = cs.textSecondary();
                }
                case DELAY -> {
                    border = cs.accent();
                    statusText = "Next server...";
                    statusColor = cs.textSecondary();
                }
                default -> {
                    border = cs.accent();
                    statusText = autoPhase.name();
                    statusColor = cs.textSecondary();
                }
            }
        }

        int bg = cs.tooltipBg();
        RenderUtils.drawRect(g, x, y, WIDTH, HEIGHT, bg);
        RenderUtils.drawBorder(g, x, y, WIDTH, HEIGHT, border);

        RenderUtils.drawText(g, "Automation", x + 4, y + 3, cs.accent());

        String progressText = (completed + failed) + "/" + total;
        int progressW = RenderUtils.scaledTextWidth(progressText);
        RenderUtils.drawText(g, progressText, x + WIDTH - progressW - 4, y + 3, cs.textPrimary());

        String serverIp = orchestrator.getCurrentServerDisplay();
        if (serverIp.length() > 22) {
            serverIp = RenderUtils.trimToWidth(serverIp, WIDTH - 12);
        }
        RenderUtils.drawText(g, serverIp, x + 4, y + 12, statusColor);

        int barX = x + 4;
        int barY = y + HEIGHT - 6;
        int barW = WIDTH - 8;
        int barH = 3;

        float progress = total > 0 ? (float) (completed + failed) / total : 0f;
        int filledW = (int) (barW * progress);

        RenderUtils.drawRect(g, barX, barY, barW, barH, cs.scrollbarTrack());
        if (filledW > 0) {
            int barColor = isLobby ? 0xFFFFAA00 : cs.accentDim();
            RenderUtils.drawRect(g, barX, barY, filledW, barH, barColor);
        }
    }

    private boolean isAutomationRunning() {
        if (orchestrator == null) return false;
        var phase = orchestrator.getPhase();
        return phase != SequenceHandler.Phase.IDLE
                && phase != SequenceHandler.Phase.COMPLETED;
    }

    public void reset() {
        overlayState = OverlayState.HIDDEN;
        slideProgress = 0f;
        elapsedTicks = 0;
        holdTimer = 0;
        cachedResult = "";
        showConfidence = true;
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
