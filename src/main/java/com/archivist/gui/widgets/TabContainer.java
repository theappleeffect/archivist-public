package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Tabbed container widget. Each tab has a name and a Panel of children.
 * Only the active tab's content is rendered and receives input.
 */
public class TabContainer extends Widget {

    private static final int TAB_HEIGHT = 14;
    private static final int TAB_PADDING = 6;

    private final List<String> tabNames = new ArrayList<>();
    private final List<Panel> tabPanels = new ArrayList<>();
    private int activeTab = 0;

    public TabContainer(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    public Panel addTab(String name) {
        tabNames.add(name);
        Panel panel = new Panel(x, y + TAB_HEIGHT, width, height - TAB_HEIGHT);
        tabPanels.add(panel);
        return panel;
    }

    public Panel getTabPanel(int index) {
        return index >= 0 && index < tabPanels.size() ? tabPanels.get(index) : null;
    }

    public Panel getTabPanel(String name) {
        int idx = tabNames.indexOf(name);
        return idx >= 0 ? tabPanels.get(idx) : null;
    }

    @Override
    public void reflow(double parentX, double parentY, double parentW, double parentH) {
        super.reflow(parentX, parentY, parentW, parentH);
        for (Panel panel : tabPanels) {
            panel.setBounds(this.x, this.y + TAB_HEIGHT, this.width, this.height - TAB_HEIGHT);
            panel.layoutChildren();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        ColorScheme cs = ColorScheme.get();

        // ── Tab Bar ─────────────────────────────────────────────────────────
        int tx = x;
        for (int i = 0; i < tabNames.size(); i++) {
            String name = tabNames.get(i);
            int tabW = RenderUtils.scaledTextWidth(name) + TAB_PADDING * 2;
            boolean active = i == activeTab;
            boolean hover = mouseX >= tx && mouseX < tx + tabW
                    && mouseY >= y && mouseY < y + TAB_HEIGHT;

            int bg = active ? cs.tabActive() : (hover ? cs.buttonHover() : cs.tab());
            RenderUtils.drawRect(g, tx, y, tabW, TAB_HEIGHT, bg);

            int textColor = active ? cs.tabTextActive() : cs.tabText();
            int textX = tx + (tabW - RenderUtils.scaledTextWidth(name)) / 2;
            int textY = y + (TAB_HEIGHT - RenderUtils.scaledFontHeight()) / 2;
            RenderUtils.drawText(g, name, textX, textY, textColor);

            if (active) {
                RenderUtils.drawHLine(g, tx, y + TAB_HEIGHT - 1, tabW, cs.accent());
            }

            tx += tabW + 1;
        }

        if (tx < x + width) {
            RenderUtils.drawRect(g, tx, y, x + width - tx, TAB_HEIGHT, cs.tab());
        }

        RenderUtils.drawHLine(g, x, y + TAB_HEIGHT, width, cs.separator());

        // ── Active Tab Content ──────────────────────────────────────────────
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            Panel panel = tabPanels.get(activeTab);
            panel.setPosition(x, y + TAB_HEIGHT);
            panel.setSize(width, height - TAB_HEIGHT);
            panel.layoutChildren();
            panel.render(g, mouseX, mouseY, delta);
        }
    }

    @Override
    public void tick() {
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            tabPanels.get(activeTab).tick();
        }
    }

    // ── Input ───────────────────────────────────────────────────────────────

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;

        if (mouseY >= y && mouseY < y + TAB_HEIGHT && button == 0) {
            int tx = x;
            for (int i = 0; i < tabNames.size(); i++) {
                int tabW = RenderUtils.scaledTextWidth(tabNames.get(i)) + TAB_PADDING * 2;
                if (mouseX >= tx && mouseX < tx + tabW) {
                    activeTab = i;
                    return true;
                }
                tx += tabW + 1;
            }
        }

        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            return tabPanels.get(activeTab).onMouseClicked(mouseX, mouseY, button);
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            return tabPanels.get(activeTab).onMouseReleased(mouseX, mouseY, button);
        }
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            return tabPanels.get(activeTab).onMouseDragged(mouseX, mouseY, button, dx, dy);
        }
        return false;
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            return tabPanels.get(activeTab).onMouseScrolled(mouseX, mouseY, hAmount, vAmount);
        }
        return false;
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            return tabPanels.get(activeTab).onKeyPressed(keyCode, scanCode, modifiers);
        }
        return false;
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            return tabPanels.get(activeTab).onCharTyped(chr, modifiers);
        }
        return false;
    }

    @Override
    public String getTextAtPoint(double px, double py) {
        if (!visible) return null;
        if (activeTab >= 0 && activeTab < tabPanels.size()) {
            return tabPanels.get(activeTab).getTextAtPoint(px, py);
        }
        return null;
    }

    public int getActiveTab() { return activeTab; }
    public void setActiveTab(int index) {
        if (index >= 0 && index < tabNames.size()) activeTab = index;
    }
    public int getTabCount() { return tabNames.size(); }
}
