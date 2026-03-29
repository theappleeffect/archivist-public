package com.archivist.gui.widgets;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Base class for all custom Archivist widgets.
 * Every widget handles its own input via on* methods.
 * No vanilla Minecraft widgets are used anywhere.
 */
public abstract class Widget {

    protected int x, y, width, height;
    protected boolean visible = true;
    protected boolean hovered = false;
    protected boolean focused = false;

    // ── Layout Anchoring ─────────────────────────────────────────────────────
    public enum Anchor {
        NONE,
        FILL,
        TOP,
        BOTTOM,
        FILL_ABOVE,
        FILL_BELOW,
        BOTTOM_RIGHT
    }

    protected Anchor anchor = Anchor.NONE;
    protected double anchorMarginTop = 0;
    protected double anchorMarginBottom = 0;
    protected double anchorMarginLeft = 0;
    protected double anchorMarginRight = 0;
    protected double fixedHeight = -1;
    protected double fixedWidth = -1;

    public Widget(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /** Render this widget. Called every frame. */
    public abstract void render(GuiGraphics g, int mouseX, int mouseY, float delta);

    /** Called once per game tick (20 tps). Optional override for animations/updates. */
    public void tick() {}

    // ── Input Methods ───────────────────────────────────────────────────────

    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        return false;
    }

    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        return false;
    }

    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean onCharTyped(char chr, int modifiers) {
        return false;
    }

    // ── Hit Testing ─────────────────────────────────────────────────────────

    public boolean containsPoint(double px, double py) {
        return visible && px >= x && px < x + width && py >= y && py < y + height;
    }

    /** Returns the copyable text at the given screen coordinates, or null if none. */
    public String getTextAtPoint(double px, double py) {
        return null;
    }

    // ── Position & Size ─────────────────────────────────────────────────────

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public void setSize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    // ── Anchor Setters ──────────────────────────────────────────────────────

    public Anchor getAnchor() { return anchor; }

    public Widget setAnchor(Anchor anchor) {
        this.anchor = anchor;
        return this;
    }

    public Widget setMargins(double top, double right, double bottom, double left) {
        this.anchorMarginTop = top;
        this.anchorMarginRight = right;
        this.anchorMarginBottom = bottom;
        this.anchorMarginLeft = left;
        return this;
    }

    public Widget setFixedHeight(double h) {
        this.fixedHeight = h;
        return this;
    }

    public Widget setFixedWidth(double w) {
        this.fixedWidth = w;
        return this;
    }

    /**
     * Recompute this widget's x/y/width/height based on its anchor
     * and the given parent content bounds.
     */
    public void reflow(double parentX, double parentY, double parentW, double parentH) {
        switch (anchor) {
            case NONE:
                break;
            case FILL:
                x = (int) (parentX + anchorMarginLeft);
                y = (int) (parentY + anchorMarginTop);
                width = (int) (parentW - anchorMarginLeft - anchorMarginRight);
                height = (int) (parentH - anchorMarginTop - anchorMarginBottom);
                break;
            case TOP:
                x = (int) (parentX + anchorMarginLeft);
                y = (int) (parentY + anchorMarginTop);
                width = (int) (parentW - anchorMarginLeft - anchorMarginRight);
                height = fixedHeight > 0 ? (int) fixedHeight : height;
                break;
            case BOTTOM:
                x = (int) (parentX + anchorMarginLeft);
                height = fixedHeight > 0 ? (int) fixedHeight : height;
                y = (int) (parentY + parentH - height - anchorMarginBottom);
                width = (int) (parentW - anchorMarginLeft - anchorMarginRight);
                break;
            case FILL_ABOVE:
                x = (int) (parentX + anchorMarginLeft);
                y = (int) (parentY + anchorMarginTop);
                width = (int) (parentW - anchorMarginLeft - anchorMarginRight);
                height = (int) (parentH - anchorMarginTop - anchorMarginBottom);
                break;
            case FILL_BELOW:
                x = (int) (parentX + anchorMarginLeft);
                y = (int) (parentY + anchorMarginTop);
                width = (int) (parentW - anchorMarginLeft - anchorMarginRight);
                height = (int) (parentH - anchorMarginTop - anchorMarginBottom);
                break;
            case BOTTOM_RIGHT:
                width = fixedWidth > 0 ? (int) fixedWidth : width;
                height = fixedHeight > 0 ? (int) fixedHeight : height;
                x = (int) (parentX + parentW - width - anchorMarginRight);
                y = (int) (parentY + parentH - height - anchorMarginBottom);
                break;
        }
    }

    // ── State ───────────────────────────────────────────────────────────────

    public boolean isVisible() { return visible; }
    public void setVisible(boolean v) { this.visible = v; }

    public boolean isHovered() { return hovered; }

    public boolean isFocused() { return focused; }
    public void setFocused(boolean f) { this.focused = f; }

    /** Update hover state based on mouse position. Call in render(). */
    protected void updateHover(int mouseX, int mouseY) {
        hovered = containsPoint(mouseX, mouseY);
    }
}
