package com.archivist.gui.panel;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.GradientConfig;
import com.archivist.gui.render.RenderUtils;
import com.archivist.gui.widgets.Widget;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * Sidebar button for the panel mode layout.
 * Renders a text label with background; supports normal, hover, active, and dimmed states.
 * In dynamic mode, supports drag-to-detach and vertical reorder.
 */
public class SidebarButton extends Widget {

    private final String label;
    private boolean active = false;
    private Runnable onClick;
    private final int preferredWidth;

    private static final int ACTIVE_BAR_WIDTH = 3;
    private static final int TEXT_PADDING = 16; // 8px each side
    private static final int GRIP_WIDTH = 6; // drag handle area on right

    // Dimmed state (detached in dynamic mode)
    private boolean dimmed = false;

    // Drag tracking
    private boolean dragging = false;
    private double dragStartX, dragStartY;
    private double dragCurrentX, dragCurrentY;

    // Callbacks
    private Consumer<SidebarButton> onDragDetach;
    private Runnable onRedock;

    // Reorder callback: called with (this, deltaY) while vertically dragging
    private Consumer<SidebarButton> onReorderStart;
    private Consumer<Float> onReorderEnd;

    // Visual reorder: smooth Y offset for animation
    private float displayYOffset = 0;
    private float targetYOffset = 0;

    // Pulse animation for redock feedback
    private int pulseTicksRemaining = 0;

    // Drag mode detection
    private static final float LERP_SPEED = 0.4f;
    private static final int DETACH_THRESHOLD_H = 30;
    private static final int REORDER_THRESHOLD_V = 10;
    private boolean dragModeDecided = false;
    private boolean dragModeIsReorder = false;

    public SidebarButton(int x, int y, int width, int height, String label, Runnable onClick) {
        super(x, y, width, height);
        this.label = label;
        this.onClick = onClick;
        this.preferredWidth = RenderUtils.scaledTextWidth(label) + TEXT_PADDING + GRIP_WIDTH;
    }

    /**
     * Returns the preferred width based on the text content plus padding.
     */
    public int getPreferredWidth() {
        return preferredWidth;
    }

    // ── Dimmed state ────────────────────────────────────────────────────

    public boolean isDimmed() { return dimmed; }
    public void setDimmed(boolean dimmed) { this.dimmed = dimmed; }

    // ── Callbacks ───────────────────────────────────────────────────────

    public void setOnDragDetach(Consumer<SidebarButton> onDragDetach) {
        this.onDragDetach = onDragDetach;
    }

    public void setOnRedock(Runnable onRedock) {
        this.onRedock = onRedock;
    }

    public void setOnReorderStart(Consumer<SidebarButton> cb) { this.onReorderStart = cb; }
    public void setOnReorderEnd(Consumer<Float> cb) { this.onReorderEnd = cb; }

    // ── Reorder animation ───────────────────────────────────────────────

    public void setTargetYOffset(float offset) { this.targetYOffset = offset; }
    public float getDisplayYOffset() { return displayYOffset; }
    public void resetDisplayOffset() { displayYOffset = 0; targetYOffset = 0; }
    public boolean isDragging() { return dragging; }
    public double getDragCurrentX() { return dragCurrentX; }
    public double getDragCurrentY() { return dragCurrentY; }

    // ── Pulse animation ─────────────────────────────────────────────────

    public void triggerPulse() { pulseTicksRemaining = 6; }

    @Override
    public void tick() {
        // Lerp display offset toward target
        if (Math.abs(displayYOffset - targetYOffset) > 0.5f) {
            displayYOffset += (targetYOffset - displayYOffset) * LERP_SPEED;
        } else {
            displayYOffset = targetYOffset;
        }
        if (pulseTicksRemaining > 0) pulseTicksRemaining--;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        updateHover(mouseX, mouseY);

        ColorScheme cs = ColorScheme.get();

        int renderY = y + (int) displayYOffset;

        // Dimmed state: render with reduced alpha, no accent bar
        if (dimmed) {
            int dimmedColor = ColorScheme.withAlpha(cs.textSecondary(), 80);
            int textX = x + ACTIVE_BAR_WIDTH + 6;
            int textY = renderY + (height - RenderUtils.scaledFontHeight()) / 2;
            RenderUtils.drawText(g, label, textX, textY, dimmedColor);

            // Hover hint for re-dock
            if (hovered) {
                int hintColor = ColorScheme.withAlpha(cs.accent(), 60);
                RenderUtils.drawRect(g, x, renderY, width, height, hintColor);
            }
            return;
        }

        // Pulse animation (brief flash after redock)
        if (pulseTicksRemaining > 0) {
            int pulseAlpha = (int)(40 * ((float) pulseTicksRemaining / 6));
            RenderUtils.drawRect(g, x, renderY, width, height,
                    ColorScheme.withAlpha(cs.accent(), pulseAlpha));
        }

        // Filled background matching GUI Button style
        int bg;
        if (active) {
            bg = cs.buttonHover();
        } else if (hovered) {
            bg = cs.buttonHover();
        } else {
            bg = cs.button();
        }

        GradientConfig btnGrad = cs.getButtonGradient();
        if (!active && !hovered && btnGrad != null && ColorScheme.gradientsEnabled()) {
            RenderUtils.drawGradientRect(g, x, renderY, width, height, btnGrad.topColor(), btnGrad.bottomColor());
        } else {
            RenderUtils.drawRect(g, x, renderY, width, height, bg);
        }

        // Border matching GUI button style
        int borderColor = ColorScheme.withAlpha(cs.windowBorder(), 60);
        RenderUtils.drawBorder(g, x, renderY, width, height, borderColor);

        // Text color matching GUI button style
        int textColor = cs.buttonText();

        // Centered text like GUI buttons
        int textW = RenderUtils.scaledTextWidth(label);
        int textX = x + (width - textW) / 2;
        int textY = renderY + (height - RenderUtils.scaledFontHeight()) / 2;
        RenderUtils.drawText(g, label, textX, textY, textColor);

        // Grip handle icon (only in dynamic mode — when detach is available)
        if (onDragDetach != null && !dimmed) {
            int gripX = x + width - GRIP_WIDTH - 4;
            int gripY = renderY;
            boolean gripHover = mouseX >= gripX && mouseX < gripX + GRIP_WIDTH
                    && mouseY >= gripY && mouseY < gripY + height;
            int gripColor = gripHover
                    ? ColorScheme.withAlpha(cs.textPrimary(), 120)
                    : ColorScheme.withAlpha(cs.textSecondary(), 50);

            // Draw three horizontal grip lines (≡)
            int lineW = 4;
            int lineX = gripX + (GRIP_WIDTH - lineW) / 2;
            int centerY = gripY + height / 2;
            for (int i = -1; i <= 1; i++) {
                int ly = centerY + i * 2;
                g.fill(lineX, ly, lineX + lineW, ly + 1, gripColor);
            }
        }
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        if (button == 0 && containsPoint(mouseX, mouseY)) {
            // Dimmed button click -> redock
            if (dimmed) {
                if (onRedock != null) onRedock.run();
                return true;
            }
            // Start potential drag
            dragging = true;
            dragStartX = mouseX;
            dragStartY = mouseY;
            dragCurrentX = mouseX;
            dragCurrentY = mouseY;
            dragModeDecided = false;
            dragModeIsReorder = false;

            // If no detach handler, just click immediately
            if (onDragDetach == null) {
                dragging = false;
                if (onClick != null) onClick.run();
                return true;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!visible || !dragging) return false;

        dragCurrentX = mouseX;
        dragCurrentY = mouseY;

        double totalDx = mouseX - dragStartX;
        double totalDy = mouseY - dragStartY;

        if (!dragModeDecided) {
            double absDx = Math.abs(totalDx);
            double absDy = Math.abs(totalDy);

            // Decide drag mode based on axis dominance
            if (absDy > REORDER_THRESHOLD_V && absDy > absDx) {
                dragModeDecided = true;
                dragModeIsReorder = true;
                if (onReorderStart != null) onReorderStart.accept(this);
            } else if (absDx > DETACH_THRESHOLD_H) {
                dragModeDecided = true;
                dragModeIsReorder = false;
            }
        }

        if (dragModeDecided) {
            if (dragModeIsReorder) {
                // Visual feedback: move this button with mouse
                displayYOffset = (float)(mouseY - dragStartY);
                return true;
            } else {
                // Horizontal drag past threshold -> detach
                double absDx = Math.abs(totalDx);
                if (absDx > DETACH_THRESHOLD_H && onDragDetach != null) {
                    dragging = false;
                    dragModeDecided = false;
                    onDragDetach.accept(this);
                    return true;
                }
            }
        }

        return true;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (!visible || !dragging) return false;

        boolean wasDragging = dragging;
        boolean wasReorder = dragModeIsReorder && dragModeDecided;
        dragging = false;
        dragModeDecided = false;

        if (wasReorder) {
            float finalOffset = displayYOffset;
            displayYOffset = 0;
            targetYOffset = 0;
            if (onReorderEnd != null) onReorderEnd.accept(finalOffset);
            return true;
        }

        // Small movement -> treat as click (but not if dimmed)
        if (wasDragging && !dimmed) {
            double totalDx = Math.abs(mouseX - dragStartX);
            double totalDy = Math.abs(mouseY - dragStartY);
            if (totalDx < 5 && totalDy < 5) {
                if (onClick != null) onClick.run();
            }
        }

        return true;
    }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getLabel() { return label; }
    public void setOnClick(Runnable onClick) { this.onClick = onClick; }
}
