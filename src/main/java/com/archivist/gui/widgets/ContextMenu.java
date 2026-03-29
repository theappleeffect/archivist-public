package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Right-click context menu widget. Shows a list of actions.
 * Designed to be used with PopupLayer.
 */
public class ContextMenu extends Widget {

    private static final int ITEM_HEIGHT = 14;
    private static final int PADDING = 3;
    private static final int MIN_WIDTH = 80;

    private final List<MenuItem> items = new ArrayList<>();

    public record MenuItem(String label, Runnable action) {}

    public ContextMenu(int x, int y) {
        super(x, y, MIN_WIDTH, PADDING * 2);
    }

    public ContextMenu addItem(String label, Runnable action) {
        items.add(new MenuItem(label, action));
        recalcSize();
        return this;
    }

    private void recalcSize() {
        int maxW = MIN_WIDTH;
        for (MenuItem item : items) {
            int w = RenderUtils.scaledTextWidth(item.label) + PADDING * 4;
            if (w > maxW) maxW = w;
        }
        width = maxW;
        height = PADDING * 2 + items.size() * ITEM_HEIGHT;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        ColorScheme cs = ColorScheme.get();

        RenderUtils.drawRect(g, x, y, width, height, cs.tooltipBg());
        RenderUtils.drawBorder(g, x, y, width, height, cs.tooltipBorder());

        int iy = y + PADDING;
        for (MenuItem item : items) {
            boolean hover = mouseX >= x && mouseX < x + width
                    && mouseY >= iy && mouseY < iy + ITEM_HEIGHT;

            if (hover) {
                RenderUtils.drawRect(g, x + 1, iy, width - 2, ITEM_HEIGHT, cs.listHover());
            }

            int textY = iy + (ITEM_HEIGHT - RenderUtils.scaledFontHeight()) / 2;
            RenderUtils.drawText(g, item.label, x + PADDING + 2, textY,
                    hover ? cs.textPrimary() : cs.textSecondary());

            iy += ITEM_HEIGHT;
        }
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || !containsPoint(mouseX, mouseY)) return false;

        int iy = y + PADDING;
        for (MenuItem item : items) {
            if (mouseY >= iy && mouseY < iy + ITEM_HEIGHT) {
                PopupLayer.close();
                item.action.run();
                return true;
            }
            iy += ITEM_HEIGHT;
        }
        return true;
    }
}
