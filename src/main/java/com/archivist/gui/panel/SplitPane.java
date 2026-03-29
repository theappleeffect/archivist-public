package com.archivist.gui.panel;

import com.archivist.gui.widgets.Widget;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A horizontal split container that positions two child widgets side by side.
 * When the right panel is hidden, the left panel takes the full width.
 */
public class SplitPane extends Widget {

    private Widget left;
    private Widget right;
    private float splitRatio = 0.4f;
    private boolean rightVisible = false;
    private static final int GAP = 2;

    public SplitPane(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    public void setLeft(Widget w) { this.left = w; }
    public void setRight(Widget w) { this.right = w; }

    public Widget getLeft() { return left; }
    public Widget getRight() { return right; }

    public void setSplitRatio(float ratio) { this.splitRatio = ratio; }

    public void setRightVisible(boolean v) {
        this.rightVisible = v;
        if (right != null) {
            right.setVisible(v);
        }
    }

    public boolean isRightVisible() { return rightVisible; }

    @Override
    public void reflow(double parentX, double parentY, double parentW, double parentH) {
        super.reflow(parentX, parentY, parentW, parentH);
        layoutChildren();
    }

    private void layoutChildren() {
        if (left == null) return;

        if (!rightVisible || right == null) {
            left.setBounds(x, y, width, height);
        } else {
            int leftW = (int) (width * splitRatio) - GAP / 2;
            int rightW = width - leftW - GAP;
            left.setBounds(x, y, leftW, height);
            right.setBounds(x + leftW + GAP, y, rightW, height);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        layoutChildren();

        if (left != null && left.isVisible()) {
            left.render(g, mouseX, mouseY, delta);
        }
        if (rightVisible && right != null && right.isVisible()) {
            right.render(g, mouseX, mouseY, delta);
        }
    }

    @Override
    public void tick() {
        if (left != null) left.tick();
        if (right != null) right.tick();
    }

    // ── Input Forwarding ────────────────────────────────────────────────

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !containsPoint(mouseX, mouseY)) return false;
        // Forward to right first if visible and contains point
        if (rightVisible && right != null && right.isVisible() && right.containsPoint(mouseX, mouseY)) {
            if (right.onMouseClicked(mouseX, mouseY, button)) return true;
        }
        if (left != null && left.isVisible() && left.containsPoint(mouseX, mouseY)) {
            if (left.onMouseClicked(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        if (rightVisible && right != null && right.isVisible()) {
            if (right.onMouseReleased(mouseX, mouseY, button)) return true;
        }
        if (left != null && left.isVisible()) {
            if (left.onMouseReleased(mouseX, mouseY, button)) return true;
        }
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!visible) return false;
        if (rightVisible && right != null && right.isVisible()) {
            if (right.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        }
        if (left != null && left.isVisible()) {
            if (left.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        }
        return false;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (!visible || !containsPoint(mouseX, mouseY)) return false;
        if (rightVisible && right != null && right.isVisible() && right.containsPoint(mouseX, mouseY)) {
            if (right.onMouseScrolled(mouseX, mouseY, hAmount, vAmount)) return true;
        }
        if (left != null && left.isVisible() && left.containsPoint(mouseX, mouseY)) {
            if (left.onMouseScrolled(mouseX, mouseY, hAmount, vAmount)) return true;
        }
        return false;
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) return false;
        if (rightVisible && right != null && right.isVisible()) {
            if (right.onKeyPressed(keyCode, scanCode, modifiers)) return true;
        }
        if (left != null && left.isVisible()) {
            if (left.onKeyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return false;
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (!visible) return false;
        if (rightVisible && right != null && right.isVisible()) {
            if (right.onCharTyped(chr, modifiers)) return true;
        }
        if (left != null && left.isVisible()) {
            if (left.onCharTyped(chr, modifiers)) return true;
        }
        return false;
    }

    @Override
    public String getTextAtPoint(double px, double py) {
        if (!visible) return null;
        if (rightVisible && right != null && right.isVisible()) {
            String text = right.getTextAtPoint(px, py);
            if (text != null) return text;
        }
        if (left != null && left.isVisible()) {
            String text = left.getTextAtPoint(px, py);
            if (text != null) return text;
        }
        return null;
    }
}
