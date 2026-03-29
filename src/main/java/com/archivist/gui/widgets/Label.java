package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Static text label widget with optional color override.
 */
public class Label extends Widget {

    private String text;
    private int color;
    private boolean useThemeColor;

    public Label(int x, int y, int width, String text) {
        super(x, y, width, RenderUtils.scaledFontHeight() + 2);
        this.text = text;
        this.color = 0;
        this.useThemeColor = true;
    }

    public Label(int x, int y, int width, String text, int color) {
        super(x, y, width, RenderUtils.scaledFontHeight() + 2);
        this.text = text;
        this.color = color;
        this.useThemeColor = false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible || text == null || text.isEmpty()) return;
        int c = useThemeColor ? ColorScheme.get().textPrimary() : color;
        int newHeight = RenderUtils.wrappedTextHeight(text, width);
        if (newHeight != height) height = Math.max(newHeight + 2, RenderUtils.scaledFontHeight() + 2);
        RenderUtils.drawWrappedText(g, text, x, y + 1, width, c);
    }

    public String getText() { return text; }

    @Override
    public String getTextAtPoint(double px, double py) {
        if (!visible || text == null || text.isEmpty()) return null;
        return containsPoint(px, py) ? text : null;
    }

    public void setText(String text) {
        this.text = text;
    }

    public void setColor(int color) {
        this.color = color;
        this.useThemeColor = false;
    }

    public void useThemeColor() {
        this.useThemeColor = true;
    }
}
