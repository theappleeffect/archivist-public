package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generic container widget. Lays out children vertically with spacing.
 * Can be used standalone or as the content area within DraggableWindow.
 * Supports scrolling when content exceeds the panel height.
 */
public class Panel extends Widget {

    protected final List<Widget> children = new ArrayList<>();
    protected int padding = 4;
    protected int spacing = 2;

    private float scrollOffset = 0;
    private float maxScroll = 0;
    private boolean scrollDragging = false;
    private double scrollDragStartY = 0;
    private float scrollDragStartOffset = 0;

    public Panel(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    @Override
    public void reflow(double parentX, double parentY, double parentW, double parentH) {
        super.reflow(parentX, parentY, parentW, parentH);
        layoutChildren();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        ColorScheme cs = ColorScheme.get();

        int contentH = getContentHeight();
        maxScroll = Math.max(0, contentH - height);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        boolean needsScroll = maxScroll > 0;
        int contentWidth = width - padding * 2;
        if (needsScroll) contentWidth -= 6;

        if (needsScroll) {
            RenderUtils.enableScissor(g, x, y, width, height);
        }

        int cy = y + padding - (int) scrollOffset;
        for (Widget child : children) {
            if (!child.isVisible()) continue;
            child.setPosition(x + padding, cy);
            int childW = child.fixedWidth > 0 ? (int) child.fixedWidth : contentWidth;
            child.setSize(childW, child.getHeight());
            if (cy + child.getHeight() > y && cy < y + height) {
                child.render(g, mouseX, mouseY, delta);
            }
            cy += child.getHeight() + spacing;
        }

        if (needsScroll) {
            RenderUtils.disableScissor(g);

            int scrollTrackX = x + width - 6;
            int scrollTrackY = y + 1;
            int scrollTrackH = height - 2;

            RenderUtils.drawRect(g, scrollTrackX, scrollTrackY, 5, scrollTrackH, cs.scrollbarTrack());

            float ratio = (float) height / contentH;
            int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
            int thumbY = scrollTrackY + (int) ((scrollTrackH - thumbH) * (scrollOffset / maxScroll));

            boolean scrollHover = mouseX >= scrollTrackX && mouseX < scrollTrackX + 5
                    && mouseY >= thumbY && mouseY < thumbY + thumbH;
            RenderUtils.drawRect(g, scrollTrackX, thumbY, 5, thumbH,
                    scrollHover ? cs.scrollbarHover() : cs.scrollbarThumb());
        }
    }

    @Override
    public void tick() {
        for (Widget child : children) {
            child.tick();
        }
    }

    // ── Input Forwarding ────────────────────────────────────────────────────

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        // Scrollbar click handling
        if (maxScroll > 0 && button == 0) {
            int scrollTrackX = x + width - 6;
            if (mouseX >= scrollTrackX && mouseX < scrollTrackX + 6 && mouseY >= y && mouseY < y + height) {
                int scrollTrackY = y + 1;
                int scrollTrackH = height - 2;
                int contentH = getContentHeight();
                float ratio = (float) height / contentH;
                int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
                int thumbY = scrollTrackY + (int) ((scrollTrackH - thumbH) * (scrollOffset / maxScroll));

                if (mouseY >= thumbY && mouseY < thumbY + thumbH) {
                    // Click on thumb — start drag
                    scrollDragging = true;
                    scrollDragStartY = mouseY;
                    scrollDragStartOffset = scrollOffset;
                } else if (mouseY < thumbY) {
                    // Click above thumb — page up
                    scrollOffset = Math.max(0, scrollOffset - height * 0.75f);
                } else {
                    // Click below thumb — page down
                    scrollOffset = Math.min(maxScroll, scrollOffset + height * 0.75f);
                }
                return true;
            }
        }

        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onMouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (scrollDragging && button == 0) {
            scrollDragging = false;
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
            int scrollTrackH = height - 2;
            int contentH = getContentHeight();
            float ratio = (float) height / contentH;
            int thumbH = Math.max(10, (int) (scrollTrackH * ratio));
            int usableTrack = scrollTrackH - thumbH;

            if (usableTrack > 0) {
                double deltaY = mouseY - scrollDragStartY;
                scrollOffset = scrollDragStartOffset + (float) (deltaY / usableTrack) * maxScroll;
                scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            }
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

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onMouseScrolled(mouseX, mouseY, hAmount, vAmount)) {
                return true;
            }
        }
        if (maxScroll > 0 && containsPoint(mouseX, mouseY)) {
            scrollOffset -= (float) vAmount * 24;
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
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
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (child.isVisible() && child.onCharTyped(chr, modifiers)) {
                return true;
            }
        }
        return false;
    }

    // ── Children Management ─────────────────────────────────────────────────

    public void addChild(Widget child) {
        children.add(child);
    }

    public void removeChild(Widget child) {
        children.remove(child);
    }

    public void clearChildren() {
        children.clear();
    }

    public List<Widget> getChildren() {
        return Collections.unmodifiableList(children);
    }

    /** Layout children vertically within bounds, applying padding and spacing. */
    public void layoutChildren() {
        int cy = y + padding;
        int contentWidth = width - padding * 2;
        if (maxScroll > 0) contentWidth -= 6;
        for (Widget child : children) {
            if (!child.isVisible()) continue;
            child.setPosition(x + padding, cy);
            int childW = child.fixedWidth > 0 ? (int) child.fixedWidth : contentWidth;
            child.setSize(childW, child.getHeight());
            cy += child.getHeight() + spacing;
        }
    }

    /** Total content height (may exceed panel height). */
    public int getContentHeight() {
        int h = padding;
        for (Widget child : children) {
            if (!child.isVisible()) continue;
            h += child.getHeight() + spacing;
        }
        return h > padding ? h - spacing + padding : padding * 2;
    }

    public void resetScroll() { scrollOffset = 0; }

    @Override
    public String getTextAtPoint(double px, double py) {
        if (!visible) return null;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget child = children.get(i);
            if (!child.isVisible()) continue;
            String text = child.getTextAtPoint(px, py);
            if (text != null) return text;
        }
        return null;
    }
    public void setPadding(int padding) { this.padding = padding; }
    public void setSpacing(int spacing) { this.spacing = spacing; }
}
