package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Floating list of options shown when a Dropdown is opened.
 * Lives in PopupLayer, NOT in the widget tree.
 */
public class DropdownPopup extends Widget {

    private static final int ITEM_HEIGHT = 12;
    private static final int MAX_VISIBLE = 8;

    private final List<String> options;
    private final int selectedIndex;
    private final IntConsumer onSelect;
    private int scrollOffset = 0;

    public DropdownPopup(List<String> options, int currentIndex,
                         int width, IntConsumer onSelect) {
        super(0, 0, width, Math.min(options.size(), MAX_VISIBLE) * ITEM_HEIGHT + 2);
        this.options = options;
        this.selectedIndex = currentIndex;
        this.onSelect = onSelect;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        ColorScheme cs = ColorScheme.get();

        RenderUtils.drawRect(g, x, y, width, height, cs.textFieldBg());
        RenderUtils.drawBorder(g, x, y, width, height, cs.textFieldFocused());

        RenderUtils.enableScissor(g, x + 1, y + 1, width - 2, height - 2);

        for (int i = 0; i < options.size(); i++) {
            int itemY = y + 1 + i * ITEM_HEIGHT - scrollOffset;
            if (itemY + ITEM_HEIGHT < y || itemY > y + height) continue;

            boolean hovered = mouseX >= x && mouseX < x + width
                    && mouseY >= itemY && mouseY < itemY + ITEM_HEIGHT;
            boolean selected = (i == selectedIndex);

            if (selected) {
                RenderUtils.drawRect(g, x + 1, itemY, width - 2, ITEM_HEIGHT, cs.listSelected());
            } else if (hovered) {
                RenderUtils.drawRect(g, x + 1, itemY, width - 2, ITEM_HEIGHT, cs.listHover());
            }

            int textColor = selected ? cs.listTextSelected() : cs.listText();
            RenderUtils.drawText(g, options.get(i), x + 3,
                    itemY + (ITEM_HEIGHT - RenderUtils.scaledFontHeight()) / 2, textColor);
        }

        RenderUtils.disableScissor(g);
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!containsPoint(mouseX, mouseY) || button != 0) return false;
        int index = (int) ((mouseY - y - 1 + scrollOffset) / ITEM_HEIGHT);
        if (index >= 0 && index < options.size()) {
            onSelect.accept(index);
        }
        return true;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY,
                                   double hAmount, double vAmount) {
        if (!containsPoint(mouseX, mouseY)) return false;
        int maxScroll = Math.max(0, options.size() * ITEM_HEIGHT - height + 2);
        scrollOffset = (int) Math.max(0, Math.min(scrollOffset - vAmount * ITEM_HEIGHT * 3, maxScroll));
        return true;
    }
}
