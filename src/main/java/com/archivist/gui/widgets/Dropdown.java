package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;

/**
 * Dropdown select widget. Shows current value, expands on click to show options.
 * The expanded option list is rendered via PopupLayer (not inline).
 */
public class Dropdown extends Widget {

    private String label;
    private List<String> options;
    private int selectedIndex = 0;
    private Consumer<String> onChange;
    private boolean expanded = false;

    private static final int ITEM_HEIGHT = 12;
    private static final int CLOSED_HEIGHT = 14;

    public Dropdown(int x, int y, int width, String label, List<String> options,
                    String selectedValue, Consumer<String> onChange) {
        super(x, y, width, CLOSED_HEIGHT);
        this.fixedWidth = width;
        this.label = label;
        this.options = options;
        this.onChange = onChange;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals(selectedValue)) {
                selectedIndex = i;
                break;
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        updateHover(mouseX, mouseY);
        ColorScheme cs = ColorScheme.get();

        if (label != null && !label.isEmpty()) {
            RenderUtils.drawText(g, label + ":", x, y + (CLOSED_HEIGHT - RenderUtils.scaledFontHeight()) / 2,
                    cs.textSecondary());
        }

        int labelW = label != null && !label.isEmpty() ? RenderUtils.scaledTextWidth(label + ": ") : 0;
        int boxX = x + labelW;
        int boxW = width - labelW;

        RenderUtils.drawRect(g, boxX, y, boxW, CLOSED_HEIGHT, cs.textFieldBg());
        RenderUtils.drawBorder(g, boxX, y, boxW, CLOSED_HEIGHT,
                expanded ? cs.textFieldFocused() : cs.textFieldBorder());

        String selected = selectedIndex >= 0 && selectedIndex < options.size()
                ? options.get(selectedIndex) : "";
        RenderUtils.drawText(g, selected, boxX + 3, y + (CLOSED_HEIGHT - RenderUtils.scaledFontHeight()) / 2,
                cs.textFieldText());

        RenderUtils.drawText(g, expanded ? "\u25B2" : "\u25BC",
                boxX + boxW - 10, y + (CLOSED_HEIGHT - RenderUtils.scaledFontHeight()) / 2,
                cs.textSecondary());
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;

        int labelW = label != null && !label.isEmpty() ? RenderUtils.scaledTextWidth(label + ": ") : 0;
        int boxX = x + labelW;
        int boxW = width - labelW;

        if (mouseX >= boxX && mouseX < boxX + boxW
                && mouseY >= y && mouseY < y + CLOSED_HEIGHT) {
            if (!expanded) {
                expanded = true;

                DropdownPopup popup = new DropdownPopup(
                        options, selectedIndex, boxW,
                        (index) -> {
                            selectedIndex = index;
                            expanded = false;
                            PopupLayer.close();
                            if (onChange != null) onChange.accept(options.get(index));
                        }
                );

                final int anchorX = boxX;
                final int anchorY = y + CLOSED_HEIGHT;
                PopupLayer.open(popup,
                        () -> new int[]{ anchorX, anchorY },
                        () -> { expanded = false; }
                );
            } else {
                PopupLayer.close();
            }
            return true;
        }

        return false;
    }

    public int getTotalHeight() {
        return expanded ? CLOSED_HEIGHT + options.size() * ITEM_HEIGHT : CLOSED_HEIGHT;
    }

    public String getSelectedValue() {
        return selectedIndex >= 0 && selectedIndex < options.size()
                ? options.get(selectedIndex) : "";
    }

    public void setSelectedValue(String value) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equals(value)) {
                selectedIndex = i;
                return;
            }
        }
    }

    public void setOptions(List<String> options) {
        this.options = options;
        if (selectedIndex >= options.size()) selectedIndex = 0;
    }

    public void close() {
        if (expanded) {
            expanded = false;
            PopupLayer.close();
        }
    }

    public boolean isExpanded() { return expanded; }
}
