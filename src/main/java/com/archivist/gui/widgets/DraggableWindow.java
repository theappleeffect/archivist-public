package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.GradientConfig;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Draggable, resizable window with title bar, close button, minimize button.
 * Children are laid out vertically in the content area with scrolling.
 */
public class DraggableWindow extends Widget {

    public static final int TITLE_BAR_HEIGHT = 16;
    private static final int CLOSE_BTN_SIZE = 12;
    private static final int MIN_BTN_SIZE = 12;
    private static final int PADDING = 4;
    private static final int SPACING = 2;

    private static final int RESIZE_ZONE = 6;
    private static final int CORNER_ZONE = 12;
    private static final int MIN_WIDTH = 150;
    private static final int MIN_HEIGHT = 80;

    private static final int SNAP_DISTANCE = 8;

    private static final float ANIM_SPEED = 0.1f;

    public enum AnimState { NONE, OPENING, CLOSING }
    private AnimState animState = AnimState.NONE;
    private float animProgress = 1f;
    public static boolean animationsEnabled = true;
    private static boolean animationsReady = false;
    private static int animReadyCountdown = 0;
    private static final int ANIM_READY_DELAY = 5;

    // Custom animation origin point (overrides taskbar fallback)
    private int animOriginX = -1, animOriginY = -1;

    private String title;
    private final List<Widget> children = new ArrayList<>();
    private boolean minimized = false;
    private boolean dragging = false;
    public boolean isDragging() { return dragging; }
    private int dragOffsetX, dragOffsetY;
    private float scrollOffset = 0;
    private float maxScroll = 0;
    private boolean scrollDragging = false;
    private double scrollDragStartY = 0;
    private float scrollDragStartOffset = 0;
    private boolean isActive = false;
    private boolean closeable = true;
    private boolean minimizable = true;
    private String id;
    private Runnable onClose;

    private boolean resizing = false;
    private boolean resizeLeft, resizeRight, resizeTop, resizeBottom;

    private List<DraggableWindow> allWindows;

    private Taskbar taskbar;

    public DraggableWindow(String id, String title, int x, int y, int width, int height) {
        super(x, y, width, height);
        this.id = id;
        this.title = title;
    }

    public void setAllWindows(List<DraggableWindow> windows) {
        this.allWindows = windows;
    }

    public void setTaskbar(Taskbar taskbar) {
        this.taskbar = taskbar;
    }

    public static void resetAnimReady() {
        animationsReady = false;
        animReadyCountdown = ANIM_READY_DELAY;
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    @Override
    public void setVisible(boolean v) {
        if (!animationsEnabled || !animationsReady) {
            this.visible = v;
            animState = AnimState.NONE;
            animProgress = 1f;
            return;
        }
        if (v) {
            if (!this.visible || animState == AnimState.CLOSING) {
                this.visible = true;
                float reversedProgress = (animState == AnimState.CLOSING) ? 1f - animProgress : 0f;
                animState = AnimState.OPENING;
                animProgress = reversedProgress;
            }
        } else {
            if (this.visible && animState != AnimState.CLOSING) {
                float reversedProgress = (animState == AnimState.OPENING) ? 1f - animProgress : 0f;
                animState = AnimState.CLOSING;
                animProgress = reversedProgress;
            }
        }
    }

    public boolean isAnimating() {
        return animState != AnimState.NONE;
    }

    /** Set a custom animation origin point (e.g. sidebar button position). */
    public void setAnimOrigin(int x, int y) {
        this.animOriginX = x;
        this.animOriginY = y;
    }

    /** Force-start the opening animation, bypassing the animationsReady delay. */
    public void forceOpenAnimation() {
        if (!animationsEnabled) return;
        this.visible = true;
        animState = AnimState.OPENING;
        animProgress = 0f;
    }

    /** Force-start the closing animation, bypassing the animationsReady delay. */
    public void forceCloseAnimation(Runnable onFinished) {
        if (!animationsEnabled) {
            this.visible = false;
            if (onFinished != null) onFinished.run();
            return;
        }
        animState = AnimState.CLOSING;
        animProgress = 0f;
        this.pendingCloseCallback = onFinished;
    }

    private Runnable pendingCloseCallback;

    private float easeIn(float t) {
        return t * t;
    }

    private float easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;

        if (!animationsReady && animReadyCountdown > 0) {
            if (--animReadyCountdown <= 0) animationsReady = true;
        }

        // ── Animation update ─────────────────────────────────────────────────
        if (animState != AnimState.NONE) {
            animProgress += ANIM_SPEED;
            if (animProgress >= 1f) {
                animProgress = 1f;
                if (animState == AnimState.CLOSING) {
                    this.visible = false;
                    animState = AnimState.NONE;
                    if (pendingCloseCallback != null) {
                        Runnable cb = pendingCloseCallback;
                        pendingCloseCallback = null;
                        cb.run();
                    }
                    return;
                }
                animState = AnimState.NONE;
            }
        }

        // ── Animation transform ──────────────────────────────────────────────
        boolean animating = animState != AnimState.NONE;
        float scale = 1f;
        float yOffset = 0f;

        if (animating) {
            int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            int windowH = minimized ? TITLE_BAR_HEIGHT : height;
            float centerX = x + width / 2f;
            float centerY = y + windowH / 2f;

            float targetX = centerX;
            float targetY = screenH;
            if (animOriginX >= 0 && animOriginY >= 0) {
                targetX = animOriginX;
                targetY = animOriginY;
            } else if (taskbar != null) {
                int[] btnCenter = taskbar.getButtonCenter(this);
                if (btnCenter != null) {
                    targetX = btnCenter[0];
                    targetY = btnCenter[1];
                }
            }

            float xOffset;
            if (animState == AnimState.OPENING) {
                float t = easeOut(animProgress);
                scale = t;
                xOffset = (targetX - centerX) * (1f - t);
                yOffset = (targetY - centerY) * (1f - t);
            } else {
                float t = easeIn(animProgress);
                scale = 1f - t;
                xOffset = (targetX - centerX) * t;
                yOffset = (targetY - centerY) * t;
            }

            if (scale < 0.01f) scale = 0.01f;

            var pose = g.pose();
            //? if >=1.21.9 {
            pose.pushMatrix();
            pose.translate(centerX + xOffset, centerY + yOffset);
            pose.scale(scale, scale);
            pose.translate(-centerX, -centerY);
            //?} else {
            /*pose.pushPose();
            pose.translate(centerX + xOffset, centerY + yOffset, 0);
            pose.scale(scale, scale, 1);
            pose.translate(-centerX, -centerY, 0);*/
            //?}
        }

        updateHover(mouseX, mouseY);
        ColorScheme cs = ColorScheme.get();

        // ── Title Bar ───────────────────────────────────────────────────────
        GradientConfig titleGrad = cs.getTitleBarGradient();
        if (titleGrad != null && ColorScheme.gradientsEnabled()) {
            RenderUtils.drawGradientRect(g, x, y, width, TITLE_BAR_HEIGHT, titleGrad.topColor(), titleGrad.bottomColor());
        } else {
            RenderUtils.drawRect(g, x, y, width, TITLE_BAR_HEIGHT, cs.titleBar());
        }

        String displayTitle = RenderUtils.trimToWidth(title, width - CLOSE_BTN_SIZE - MIN_BTN_SIZE - 12);
        TextSelectionManager.setEnabled(false);
        RenderUtils.drawText(g, displayTitle, x + 4, y + (TITLE_BAR_HEIGHT - RenderUtils.scaledFontHeight()) / 2, cs.titleText());
        TextSelectionManager.setEnabled(true);

        if (closeable) {
            int closeBtnX = x + width - CLOSE_BTN_SIZE - 2;
            int closeBtnY = y + 2;
            boolean closeHover = mouseX >= closeBtnX && mouseX < closeBtnX + CLOSE_BTN_SIZE
                    && mouseY >= closeBtnY && mouseY < closeBtnY + CLOSE_BTN_SIZE;
            int closeColor = closeHover ? ColorScheme.lighten(cs.closeButton(), 0.15f) : cs.closeButton();
            RenderUtils.drawText(g, "\u2715", closeBtnX + 2, closeBtnY + 1, closeColor);
        }

        if (minimizable) {
            int minBtnX = x + width - (closeable ? CLOSE_BTN_SIZE + MIN_BTN_SIZE + 4 : MIN_BTN_SIZE + 2);
            int minBtnY = y + 2;
            boolean minHover = mouseX >= minBtnX && mouseX < minBtnX + MIN_BTN_SIZE
                    && mouseY >= minBtnY && mouseY < minBtnY + MIN_BTN_SIZE;
            int minColor = minHover ? cs.accent() : cs.minimizeButton();
            RenderUtils.drawText(g, minimized ? "\u25B4" : "\u25BE", minBtnX + 3, minBtnY + 1, minColor);
        }

        if (minimized) {
            RenderUtils.drawBorder(g, x, y, width, TITLE_BAR_HEIGHT,
                    isActive ? cs.windowBorderActive() : cs.windowBorder());
            if (animating) {
                //? if >=1.21.9
                g.pose().popMatrix();
                //? if <1.21.9
                /*g.pose().popPose();*/
            }
            return;
        }

        // ── Content Area ────────────────────────────────────────────────────
        int contentY = y + TITLE_BAR_HEIGHT;
        int contentH = height - TITLE_BAR_HEIGHT;

        GradientConfig winGrad = cs.getWindowBackgroundGradient();
        if (winGrad != null && ColorScheme.gradientsEnabled()) {
            RenderUtils.drawGradientRect(g, x, y + TITLE_BAR_HEIGHT, width, height - TITLE_BAR_HEIGHT, winGrad.topColor(), winGrad.bottomColor());
        } else {
            RenderUtils.drawRect(g, x, y + TITLE_BAR_HEIGHT, width, height - TITLE_BAR_HEIGHT, cs.windowBackground());
        }

        RenderUtils.drawBorder(g, x, y, width, height,
                isActive ? cs.windowBorderActive() : cs.windowBorder());

        int totalContentH = computeContentHeight();
        maxScroll = Math.max(0, totalContentH - contentH);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        reflowChildren();

        RenderUtils.enableScissor(g, x + 1, contentY, width - 2, contentH - 1);

        int cy = contentY + PADDING - (int) scrollOffset;
        int contentWidth = width - PADDING * 2;
        if (maxScroll > 0) contentWidth -= 6;

        for (Widget child : children) {
            if (!child.isVisible()) continue;
            if (child.getAnchor() == Widget.Anchor.NONE) {
                child.setPosition(x + PADDING, cy);
                int childW = child.fixedWidth > 0 ? (int) child.fixedWidth : contentWidth;
                child.setSize(childW, child.getHeight());
                if (cy + child.getHeight() > contentY && cy < contentY + contentH) {
                    child.render(g, mouseX, mouseY, delta);
                }
                cy += child.getHeight() + SPACING;
            } else {
                child.render(g, mouseX, mouseY, delta);
            }
        }

        RenderUtils.disableScissor(g);

        // ── Scrollbar ───────────────────────────────────────────────────────
        if (maxScroll > 0) {
            int scrollTrackX = x + width - 6;
            int scrollTrackY = contentY + 1;
            int scrollTrackH = contentH - 2;

            RenderUtils.drawRect(g, scrollTrackX, scrollTrackY, 5, scrollTrackH, cs.scrollbarTrack());

            float ratio = (float) contentH / totalContentH;
            int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
            int thumbY = scrollTrackY + (int) ((scrollTrackH - thumbH) * (scrollOffset / maxScroll));

            boolean scrollHover = mouseX >= scrollTrackX && mouseX < scrollTrackX + 5
                    && mouseY >= thumbY && mouseY < thumbY + thumbH;
            RenderUtils.drawRect(g, scrollTrackX, thumbY, 5, thumbH,
                    scrollHover ? cs.scrollbarHover() : cs.scrollbarThumb());
        }

        // ── Resize corner indicators ─────────────────────────────────────────
        int cornerColor = ColorScheme.withAlpha(cs.accent(), 128);
        // Bottom-right
        g.fill(x + width - 2, y + height - 1, x + width, y + height, cornerColor);
        g.fill(x + width - 4, y + height - 1, x + width, y + height, cornerColor);
        g.fill(x + width - 2, y + height - 3, x + width, y + height - 2, cornerColor);
        // Bottom-left
        g.fill(x, y + height - 1, x + 2, y + height, cornerColor);
        g.fill(x, y + height - 1, x + 4, y + height, cornerColor);
        g.fill(x, y + height - 3, x + 2, y + height - 2, cornerColor);
        // Top-right
        g.fill(x + width - 2, y, x + width, y + 1, cornerColor);
        g.fill(x + width - 4, y, x + width, y + 1, cornerColor);
        g.fill(x + width - 2, y + 2, x + width, y + 3, cornerColor);
        // Top-left
        g.fill(x, y, x + 2, y + 1, cornerColor);
        g.fill(x, y, x + 4, y + 1, cornerColor);
        g.fill(x, y + 2, x + 2, y + 3, cornerColor);

        // ── Animation cleanup ────────────────────────────────────────────────
        if (animating) {
            //? if >=1.21.9
            g.pose().popMatrix();
            //? if <1.21.9
            /*g.pose().popPose();*/
        }
    }

    private int computeContentHeight() {
        int h = PADDING;
        for (Widget child : children) {
            if (!child.isVisible()) continue;
            if (child.getAnchor() != Widget.Anchor.NONE) continue;
            h += child.getHeight() + SPACING;
        }
        return h > PADDING ? h - SPACING + PADDING : PADDING * 2;
    }

    @Override
    public void tick() {
        for (Widget child : children) child.tick();
    }

    // ── Input ───────────────────────────────────────────────────────────────

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || animState != AnimState.NONE || !containsPoint(mouseX, mouseY)) return false;

        if (closeable && button == 0) {
            int closeBtnX = x + width - CLOSE_BTN_SIZE - 2;
            int closeBtnY = y + 2;
            if (mouseX >= closeBtnX && mouseX < closeBtnX + CLOSE_BTN_SIZE
                    && mouseY >= closeBtnY && mouseY < closeBtnY + CLOSE_BTN_SIZE) {
                if (onClose != null) {
                    onClose.run(); // Let onClose set up close animation before hiding
                } else {
                    setVisible(false);
                }
                return true;
            }
        }

        if (minimizable && button == 0) {
            int minBtnX = x + width - (closeable ? CLOSE_BTN_SIZE + MIN_BTN_SIZE + 4 : MIN_BTN_SIZE + 2);
            int minBtnY = y + 2;
            if (mouseX >= minBtnX && mouseX < minBtnX + MIN_BTN_SIZE
                    && mouseY >= minBtnY && mouseY < minBtnY + MIN_BTN_SIZE) {
                minimized = !minimized;
                return true;
            }
        }

        if (button == 0 && !minimized && isOnResizeZone(mouseX, mouseY)) {
            resizing = true;
            return true;
        }

        if (button == 0 && mouseY >= y && mouseY < y + TITLE_BAR_HEIGHT) {
            dragging = true;
            dragOffsetX = (int) mouseX - x;
            dragOffsetY = (int) mouseY - y;
            return true;
        }

        // Scrollbar click handling
        if (!minimized && maxScroll > 0 && button == 0) {
            int scrollTrackX = x + width - 6;
            int contentY = y + TITLE_BAR_HEIGHT;
            int contentH = height - TITLE_BAR_HEIGHT;
            if (mouseX >= scrollTrackX && mouseX < scrollTrackX + 6
                    && mouseY >= contentY && mouseY < contentY + contentH) {
                int scrollTrackY = contentY + 1;
                int scrollTrackH = contentH - 2;
                int totalContentH = computeContentHeight();
                float ratio = (float) contentH / totalContentH;
                int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
                int thumbY = scrollTrackY + (int) ((scrollTrackH - thumbH) * (scrollOffset / maxScroll));

                if (mouseY >= thumbY && mouseY < thumbY + thumbH) {
                    scrollDragging = true;
                    scrollDragStartY = mouseY;
                    scrollDragStartOffset = scrollOffset;
                } else if (mouseY < thumbY) {
                    scrollOffset = Math.max(0, scrollOffset - contentH * 0.75f);
                } else {
                    scrollOffset = Math.min(maxScroll, scrollOffset + contentH * 0.75f);
                }
                return true;
            }
        }

        if (!minimized) {
            unfocusAllTextFields();
            for (int i = children.size() - 1; i >= 0; i--) {
                Widget child = children.get(i);
                if (child.isVisible() && child.onMouseClicked(mouseX, mouseY, button)) {
                    return true;
                }
            }
        }

        return true;
    }

    private boolean isOnResizeZone(double mx, double my) {
        resizeLeft = mx >= x && mx <= x + RESIZE_ZONE
                && my >= y + RESIZE_ZONE && my <= y + height - RESIZE_ZONE;
        resizeRight = mx >= x + width - RESIZE_ZONE && mx <= x + width
                && my >= y + RESIZE_ZONE && my <= y + height - RESIZE_ZONE;
        resizeTop = my >= y && my <= y + RESIZE_ZONE
                && mx >= x + RESIZE_ZONE && mx <= x + width - RESIZE_ZONE;
        resizeBottom = my >= y + height - RESIZE_ZONE && my <= y + height
                && mx >= x + RESIZE_ZONE && mx <= x + width - RESIZE_ZONE;

        // Corner zones (override edge detection for diagonal resize)
        if (mx >= x + width - CORNER_ZONE && my >= y + height - CORNER_ZONE) {
            resizeRight = true; resizeBottom = true;
        }
        if (mx >= x && mx <= x + CORNER_ZONE && my >= y + height - CORNER_ZONE) {
            resizeLeft = true; resizeBottom = true;
        }
        if (mx >= x + width - CORNER_ZONE && my >= y && my <= y + CORNER_ZONE) {
            resizeRight = true; resizeTop = true;
        }
        if (mx >= x && mx <= x + CORNER_ZONE && my >= y && my <= y + CORNER_ZONE) {
            resizeLeft = true; resizeTop = true;
        }
        return resizeLeft || resizeRight || resizeTop || resizeBottom;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (scrollDragging && button == 0) {
            scrollDragging = false;
            return true;
        }
        if (resizing && button == 0) {
            resizing = false;
            resizeLeft = resizeRight = resizeTop = resizeBottom = false;
            reflowChildren();
            return true;
        }
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onMouseReleased(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (scrollDragging && button == 0) {
            int contentH = height - TITLE_BAR_HEIGHT;
            int scrollTrackH = contentH - 2;
            int totalContentH = computeContentHeight();
            float ratio = (float) contentH / totalContentH;
            int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
            int usableTrack = scrollTrackH - thumbH;
            if (usableTrack > 0) {
                double deltaY = mouseY - scrollDragStartY;
                scrollOffset = scrollDragStartOffset + (float) (deltaY / usableTrack) * maxScroll;
                scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            }
            return true;
        }
        if (resizing) {
            if (resizeRight) width = Math.max(MIN_WIDTH, (int) (mouseX - x));
            if (resizeBottom) height = Math.max(MIN_HEIGHT, (int) (mouseY - y));
            if (resizeLeft) {
                int newX = (int) Math.min(mouseX, x + width - MIN_WIDTH);
                width += (x - newX);
                x = newX;
            }
            if (resizeTop) {
                int newY = (int) Math.min(mouseY, y + height - MIN_HEIGHT);
                height += (y - newY);
                y = newY;
            }
            reflowChildren();
            return true;
        }
        if (dragging) {
            int rawX = (int) mouseX - dragOffsetX;
            int rawY = (int) mouseY - dragOffsetY;
            int[] snapped = applySnapping(rawX, rawY);
            x = snapped[0];
            y = snapped[1];
            return true;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onMouseDragged(mouseX, mouseY, button, dx, dy)) {
                return true;
            }
        }
        return false;
    }

    private int[] applySnapping(int newX, int newY) {
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        List<int[]> xCandidates = new ArrayList<>();
        List<int[]> yCandidates = new ArrayList<>();

        // Determine screen edges accounting for taskbar position
        int leftEdge = 0;
        int topEdge = 0;
        int rightEdge = screenW;
        int bottomEdge = screenH;
        if (taskbar != null) {
            switch (taskbar.getPosition()) {
                case LEFT   -> leftEdge = Taskbar.TASKBAR_WIDTH;
                case TOP    -> topEdge = Taskbar.TASKBAR_HEIGHT;
                default     -> bottomEdge = screenH - Taskbar.TASKBAR_HEIGHT;
            }
        }

        xCandidates.add(new int[]{leftEdge, Math.abs(newX - leftEdge)});
        xCandidates.add(new int[]{rightEdge - width, Math.abs(newX + width - rightEdge)});
        yCandidates.add(new int[]{topEdge, Math.abs(newY - topEdge)});
        yCandidates.add(new int[]{bottomEdge - height,
                Math.abs(newY + height - bottomEdge)});

        if (allWindows != null) {
            for (DraggableWindow other : allWindows) {
                if (other == this || !other.visible) continue;

                xCandidates.add(new int[]{other.x - width, Math.abs((newX + width) - other.x)});
                xCandidates.add(new int[]{other.x + other.width, Math.abs(newX - (other.x + other.width))});
                xCandidates.add(new int[]{other.x, Math.abs(newX - other.x)});

                yCandidates.add(new int[]{other.y - height, Math.abs((newY + height) - other.y)});
                yCandidates.add(new int[]{other.y + other.height, Math.abs(newY - (other.y + other.height))});
                yCandidates.add(new int[]{other.y, Math.abs(newY - other.y)});
            }
        }

        int bestSnapX = newX;
        int bestSnapDistX = SNAP_DISTANCE + 1;
        for (int[] candidate : xCandidates) {
            if (candidate[1] < SNAP_DISTANCE && candidate[1] < bestSnapDistX) {
                bestSnapX = candidate[0];
                bestSnapDistX = candidate[1];
            }
        }

        int bestSnapY = newY;
        int bestSnapDistY = SNAP_DISTANCE + 1;
        for (int[] candidate : yCandidates) {
            if (candidate[1] < SNAP_DISTANCE && candidate[1] < bestSnapDistY) {
                bestSnapY = candidate[0];
                bestSnapDistY = candidate[1];
            }
        }

        return new int[]{
                bestSnapDistX <= SNAP_DISTANCE ? bestSnapX : newX,
                bestSnapDistY <= SNAP_DISTANCE ? bestSnapY : newY
        };
    }

    /** Reflow anchored children based on current content bounds. */
    public void reflowChildren() {
        double cx = x + PADDING;
        double cy = y + TITLE_BAR_HEIGHT;
        double cw = width - PADDING * 2;
        double ch = height - TITLE_BAR_HEIGHT - PADDING;

        for (Widget child : children) {
            child.reflow(cx, cy, cw, ch);
        }
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (!visible || minimized || !containsPoint(mouseX, mouseY)) return false;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onMouseScrolled(mouseX, mouseY, hAmount, vAmount)) {
                return true;
            }
        }
        if (maxScroll > 0) {
            scrollOffset -= (float) vAmount * 24;
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible || minimized) return false;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onKeyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (!visible || minimized) return false;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onCharTyped(chr, modifiers)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsPoint(double px, double py) {
        if (!visible) return false;
        int h = minimized ? TITLE_BAR_HEIGHT : height;
        int rz = RESIZE_ZONE;
        return px >= x - rz && px < x + width + rz && py >= y - rz && py < y + h + rz;
    }

    public boolean containsPointExact(double px, double py) {
        if (!visible) return false;
        int h = minimized ? TITLE_BAR_HEIGHT : height;
        return px >= x && px < x + width && py >= y && py < y + h;
    }

    private void unfocusAllTextFields() {
        for (Widget child : children) {
            if (child instanceof TextField tf) tf.setFocused(false);
            if (child instanceof Panel p) {
                for (Widget pc : p.getChildren()) {
                    if (pc instanceof TextField tf) tf.setFocused(false);
                }
            }
        }
    }

    // ── Cursor Hints ─────────────────────────────────────────────────────────

    public enum CursorType { ARROW, MOVE, H_RESIZE, V_RESIZE, NWSE_RESIZE, NESW_RESIZE, HAND }

    public CursorType getCursorType(double mouseX, double mouseY) {
        if (!visible || animState != AnimState.NONE) return CursorType.ARROW;

        // Active drag/resize takes priority
        if (dragging) return CursorType.MOVE;
        if (resizing) {
            boolean h = resizeLeft || resizeRight;
            boolean v = resizeTop || resizeBottom;
            if (h && v) {
                boolean nwse = (resizeLeft && resizeTop) || (resizeRight && resizeBottom);
                return nwse ? CursorType.NWSE_RESIZE : CursorType.NESW_RESIZE;
            }
            return h ? CursorType.H_RESIZE : CursorType.V_RESIZE;
        }

        if (!containsPoint(mouseX, mouseY)) return CursorType.ARROW;

        // Close / minimize buttons
        if (closeable) {
            int closeBtnX = x + width - CLOSE_BTN_SIZE - 2;
            int closeBtnY = y + 2;
            if (mouseX >= closeBtnX && mouseX < closeBtnX + CLOSE_BTN_SIZE
                    && mouseY >= closeBtnY && mouseY < closeBtnY + CLOSE_BTN_SIZE)
                return CursorType.HAND;
        }
        int minBtnX = x + width - CLOSE_BTN_SIZE - MIN_BTN_SIZE - 4;
        int minBtnY = y + 2;
        if (mouseX >= minBtnX && mouseX < minBtnX + MIN_BTN_SIZE
                && mouseY >= minBtnY && mouseY < minBtnY + MIN_BTN_SIZE)
            return CursorType.HAND;

        // Resize zones (check before title bar since corners overlap)
        if (!minimized) {
            // Save/restore resize flags since isOnResizeZone mutates them
            boolean sL = resizeLeft, sR = resizeRight, sT = resizeTop, sB = resizeBottom;
            boolean onResize = isOnResizeZone(mouseX, mouseY);
            boolean rL = resizeLeft, rR = resizeRight, rT = resizeTop, rB = resizeBottom;
            resizeLeft = sL; resizeRight = sR; resizeTop = sT; resizeBottom = sB;

            if (onResize) {
                boolean h = rL || rR;
                boolean v = rT || rB;
                if (h && v) {
                    boolean nwse = (rL && rT) || (rR && rB);
                    return nwse ? CursorType.NWSE_RESIZE : CursorType.NESW_RESIZE;
                }
                return h ? CursorType.H_RESIZE : CursorType.V_RESIZE;
            }
        }

        // Title bar = drag
        if (mouseY >= y && mouseY < y + TITLE_BAR_HEIGHT) return CursorType.MOVE;

        return CursorType.ARROW;
    }

    // ── Children Management ─────────────────────────────────────────────────

    public void addChild(Widget child) { children.add(child); }
    public void removeChild(Widget child) { children.remove(child); }
    public void clearChildren() { children.clear(); }
    public List<Widget> getChildren() { return Collections.unmodifiableList(children); }

    // ── State ───────────────────────────────────────────────────────────────

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getId() { return id; }
    public boolean isMinimized() { return minimized; }
    public void setMinimized(boolean minimized) { this.minimized = minimized; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }
    public boolean isCloseable() { return closeable; }
    public void setCloseable(boolean closeable) { this.closeable = closeable; }
    public boolean isMinimizable() { return minimizable; }
    public void setMinimizable(boolean minimizable) { this.minimizable = minimizable; }

    /** Force the window into dragging state immediately (used when detaching from sidebar). */
    public void startDragging(double mouseX, double mouseY) {
        dragging = true;
        dragOffsetX = (int) mouseX - x;
        dragOffsetY = (int) mouseY - y;
    }
    public void scrollToBottom() { scrollOffset = maxScroll; }
    public void resetScroll() { scrollOffset = 0; }

    @Override
    public String getTextAtPoint(double px, double py) {
        if (!visible || animState != AnimState.NONE) return null;
        if (!minimized && py >= y + TITLE_BAR_HEIGHT && py < y + height && px >= x && px < x + width) {
            for (int i = children.size() - 1; i >= 0; i--) {
                Widget child = children.get(i);
                if (!child.isVisible()) continue;
                String text = child.getTextAtPoint(px, py);
                if (text != null) return text;
            }
        }
        return null;
    }

    public void bringToFront() {
        if (allWindows != null) {
            allWindows.remove(this);
            allWindows.add(this);
        }
    }
}
