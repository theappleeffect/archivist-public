package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.GradientConfig;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Taskbar showing one button per window.
 * Supports bottom, top, and left (side) positions.
 * Top/bottom modes center buttons horizontally.
 * Includes a small reset "r" button on the far edge.
 */
public class Taskbar extends Widget {

    public static final int TASKBAR_HEIGHT = 18;
    public static final int TASKBAR_WIDTH = 80;
    private static final int BUTTON_PADDING = 4;
    private static final int BUTTON_SPACING = 2;
    private static final float SLIDE_ANIM_SPEED = 0.09f;

    public enum Position { BOTTOM, TOP, LEFT }

    private List<DraggableWindow> windows;
    private int screenWidth;
    private int screenHeight;
    private DraggableWindow activeWindow = null;
    private Position position = Position.BOTTOM;
    private boolean slideAnimating = false;
    private float slideAnimProgress = 0f;
    private boolean slideOut = false;
    private boolean slidOut = false;

    public void setup(List<DraggableWindow> windows, int screenWidth) {
        this.windows = windows;
        this.screenWidth = screenWidth;
        updateDimensions();
    }

    public Taskbar() {
        super(0, 0, 0, TASKBAR_HEIGHT);
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public Position getPosition() {
        return position;
    }

    public void startSlideIn() {
        slideAnimating = true;
        slideAnimProgress = 0f;
        slideOut = false;
        slidOut = false;
    }

    public void startSlideOut() {
        slideAnimating = true;
        slideAnimProgress = 0f;
        slideOut = true;
    }

    public boolean isSlideAnimating() {
        return slideAnimating;
    }

    public void updatePosition(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        switch (position) {
            case TOP -> {
                this.x = 0;
                this.y = 0;
                this.width = screenWidth;
                this.height = TASKBAR_HEIGHT;
            }
            case LEFT -> {
                this.x = 0;
                this.y = 0;
                this.width = TASKBAR_WIDTH;
                this.height = screenHeight;
            }
            default -> { // BOTTOM
                this.x = 0;
                this.y = screenHeight - TASKBAR_HEIGHT;
                this.width = screenWidth;
                this.height = TASKBAR_HEIGHT;
            }
        }
    }

    private void updateDimensions() {
        if (position == Position.LEFT) {
            this.width = TASKBAR_WIDTH;
        } else {
            this.width = screenWidth;
            this.height = TASKBAR_HEIGHT;
        }
    }

    /** Returns the thickness of the taskbar on the edge it occupies. */
    public int getEdgeThickness() {
        return position == Position.LEFT ? TASKBAR_WIDTH : TASKBAR_HEIGHT;
    }

    // ── Compute total width of all buttons (for centering) ──────────────

    private int computeTotalButtonsWidth() {
        if (windows == null || windows.isEmpty()) return 0;
        int total = 0;
        for (DraggableWindow window : windows) {
            int btnW = RenderUtils.scaledTextWidth(window.getTitle()) + 12;
            if (btnW < 40) btnW = 40;
            total += btnW + BUTTON_SPACING;
        }
        return total - BUTTON_SPACING; // remove trailing spacing
    }

    // ── Render ──────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible || windows == null) return;
        ColorScheme cs = ColorScheme.get();

        // Slide animation
        if (slidOut) return; // fully slid out — don't render
        boolean sliding = slideAnimating;
        if (sliding) {
            slideAnimProgress += SLIDE_ANIM_SPEED;
            if (slideAnimProgress >= 1f) {
                slideAnimProgress = 1f;
                slideAnimating = false;
                sliding = false;
                if (slideOut) {
                    slidOut = true;
                    return; // slide-out complete — stop rendering
                }
            }
        }

        if (sliding) {
            float eased = slideOut
                    ? slideAnimProgress * slideAnimProgress                       // ease-in for exit
                    : 1f - (1f - slideAnimProgress) * (1f - slideAnimProgress);   // ease-out for enter
            // slide-in: offset full → 0 (entering); slide-out: offset 0 → full (leaving)
            float travel = slideOut ? eased : (1f - eased);
            float offsetX = 0f;
            float offsetY = 0f;
            switch (position) {
                case BOTTOM -> offsetY = travel * TASKBAR_HEIGHT;
                case TOP -> offsetY = -travel * TASKBAR_HEIGHT;
                case LEFT -> offsetX = -travel * TASKBAR_WIDTH;
            }
            //? if >=1.21.9 {
            g.pose().pushMatrix();
            g.pose().translate(offsetX, offsetY);
            //?} else {
            /*g.pose().pushPose();
            g.pose().translate(offsetX, offsetY, 0);*/
            //?}
        }

        GradientConfig taskGrad = cs.getTaskbarGradient();
        if (taskGrad != null && ColorScheme.gradientsEnabled()) {
            RenderUtils.drawGradientRect(g, x, y, width, height, taskGrad.topColor(), taskGrad.bottomColor());
        } else {
            RenderUtils.drawRect(g, x, y, width, height, cs.taskbar());
        }

        if (position == Position.LEFT) {
            RenderUtils.drawVLine(g, x + width - 1, y, height, cs.separator());
            renderVertical(g, mouseX, mouseY, cs);
        } else {
            int sepY = (position == Position.TOP) ? y + height - 1 : y;
            RenderUtils.drawHLine(g, x, sepY, width, cs.separator());
            renderHorizontal(g, mouseX, mouseY, cs);
        }

        if (sliding) {
            //? if >=1.21.9
            g.pose().popMatrix();
            //? if <1.21.9
            /*g.pose().popPose();*/
        }
    }

    private void renderHorizontal(GuiGraphics g, int mouseX, int mouseY, ColorScheme cs) {
        // Center buttons horizontally
        int totalW = computeTotalButtonsWidth();
        int bx = x + (width - totalW) / 2;
        int by = y + 3;
        int btnH = TASKBAR_HEIGHT - 6;

        for (DraggableWindow window : windows) {
            String title = window.getTitle();
            int btnW = RenderUtils.scaledTextWidth(title) + 12;
            if (btnW < 40) btnW = 40;

            boolean isOpen = window.isVisible();
            boolean isActive = (window == activeWindow && isOpen);
            boolean hover = mouseX >= bx && mouseX < bx + btnW
                    && mouseY >= by && mouseY < by + btnH;

            int bg;
            if (isActive) {
                bg = cs.taskbarButtonActive();
            } else if (hover) {
                bg = cs.buttonHover();
            } else {
                bg = cs.taskbarButton();
            }

            RenderUtils.drawRect(g, bx, by, btnW, btnH, bg);

            if (isOpen) {
                RenderUtils.drawHLine(g, bx, by + btnH - 1, btnW, cs.accent());
            }

            int textColor = isOpen ? cs.taskbarText() : cs.textSecondary();
            int textX = bx + (btnW - RenderUtils.scaledTextWidth(title)) / 2;
            int textY = by + (btnH - RenderUtils.scaledFontHeight()) / 2;
            RenderUtils.drawText(g, title, textX, textY, textColor);

            bx += btnW + BUTTON_SPACING;
        }
    }

    private void renderVertical(GuiGraphics g, int mouseX, int mouseY, ColorScheme cs) {
        int bx = x + BUTTON_PADDING;
        int by = y + BUTTON_PADDING;
        int btnW = TASKBAR_WIDTH - BUTTON_PADDING * 2;
        int btnH = TASKBAR_HEIGHT - 6;

        for (DraggableWindow window : windows) {
            String title = window.getTitle();

            boolean isOpen = window.isVisible();
            boolean isActive = (window == activeWindow && isOpen);
            boolean hover = mouseX >= bx && mouseX < bx + btnW
                    && mouseY >= by && mouseY < by + btnH;

            int bg;
            if (isActive) {
                bg = cs.taskbarButtonActive();
            } else if (hover) {
                bg = cs.buttonHover();
            } else {
                bg = cs.taskbarButton();
            }

            RenderUtils.drawRect(g, bx, by, btnW, btnH, bg);

            if (isOpen) {
                RenderUtils.drawVLine(g, bx, by, btnH, cs.accent());
            }

            int textColor = isOpen ? cs.taskbarText() : cs.textSecondary();
            String displayTitle = RenderUtils.trimToWidth(title, btnW - 6);
            int textX = bx + 4;
            int textY = by + (btnH - RenderUtils.scaledFontHeight()) / 2;
            RenderUtils.drawText(g, displayTitle, textX, textY, textColor);

            by += btnH + BUTTON_SPACING;
        }
    }

    // ── Input ───────────────────────────────────────────────────────────

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || windows == null || button != 0) return false;
        if (!containsPoint(mouseX, mouseY)) return false;

        if (position == Position.LEFT) {
            return handleVerticalClick(mouseX, mouseY);
        } else {
            return handleHorizontalClick(mouseX, mouseY);
        }
    }

    private boolean handleHorizontalClick(double mouseX, double mouseY) {
        int totalW = computeTotalButtonsWidth();
        int bx = x + (width - totalW) / 2;
        int by = y + 3;
        int btnH = TASKBAR_HEIGHT - 6;

        for (DraggableWindow window : windows) {
            String title = window.getTitle();
            int btnW = RenderUtils.scaledTextWidth(title) + 12;
            if (btnW < 40) btnW = 40;

            if (mouseX >= bx && mouseX < bx + btnW
                    && mouseY >= by && mouseY < by + btnH) {
                toggleWindow(window);
                return true;
            }

            bx += btnW + BUTTON_SPACING;
        }
        return true;
    }

    private boolean handleVerticalClick(double mouseX, double mouseY) {
        int bx = x + BUTTON_PADDING;
        int by = y + BUTTON_PADDING;
        int btnW = TASKBAR_WIDTH - BUTTON_PADDING * 2;
        int btnH = TASKBAR_HEIGHT - 6;

        for (DraggableWindow window : windows) {
            if (mouseX >= bx && mouseX < bx + btnW
                    && mouseY >= by && mouseY < by + btnH) {
                toggleWindow(window);
                return true;
            }
            by += btnH + BUTTON_SPACING;
        }
        return true;
    }

    private void toggleWindow(DraggableWindow window) {
        if (window == activeWindow && window.isVisible()) {
            window.setVisible(false);
            activeWindow = null;
        } else {
            window.setVisible(true);
            window.setMinimized(false);
            activeWindow = window;
        }
    }

    public void setActiveWindow(DraggableWindow window) {
        this.activeWindow = window;
    }

    public DraggableWindow getActiveWindow() {
        return activeWindow;
    }

    public int[] getButtonCenter(DraggableWindow target) {
        if (windows == null) return null;

        if (position == Position.LEFT) {
            int bx = x + BUTTON_PADDING;
            int by = y + BUTTON_PADDING;
            int btnW = TASKBAR_WIDTH - BUTTON_PADDING * 2;
            int btnH = TASKBAR_HEIGHT - 6;

            for (DraggableWindow window : windows) {
                if (window == target) {
                    return new int[]{ bx + btnW / 2, by + btnH / 2 };
                }
                by += btnH + BUTTON_SPACING;
            }
        } else {
            int totalW = computeTotalButtonsWidth();
            int bx = x + (width - totalW) / 2;
            int by = y + 3;
            int btnH = TASKBAR_HEIGHT - 6;

            for (DraggableWindow window : windows) {
                String title = window.getTitle();
                int btnW = RenderUtils.scaledTextWidth(title) + 12;
                if (btnW < 40) btnW = 40;

                if (window == target) {
                    return new int[]{ bx + btnW / 2, by + btnH / 2 };
                }
                bx += btnW + BUTTON_SPACING;
            }
        }
        return null;
    }

    @Override
    public boolean containsPoint(double px, double py) {
        return visible && px >= x && px < x + width && py >= y && py < y + height;
    }
}
