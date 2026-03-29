package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

/**
 * A collapsible section with a clickable header that toggles content visibility.
 * Header shows ▼/▶ arrow + section name. Content is a Panel that holds child widgets.
 */
public class CollapsibleSection extends Widget {

    private static final int HEADER_HEIGHT = 12;
    private static final int HEADER_PAD = 2;

    private final String title;
    private final Panel content;
    private boolean expanded = true;
    private float animProgress = 1.0f;

    public CollapsibleSection(int x, int y, int width, String title) {
        super(x, y, width, HEADER_HEIGHT);
        this.title = title;
        this.content = new Panel(x, y + HEADER_HEIGHT + HEADER_PAD, width, 0);
        this.content.setPadding(2);
        this.content.setSpacing(1);
    }

    public Panel getContent() { return content; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }

    /** Add a child widget to the content panel. */
    public void addChild(Widget child) {
        content.addChild(child);
    }

    @Override
    public int getHeight() {
        content.layoutChildren();
        int fullHeight = HEADER_HEIGHT + HEADER_PAD + content.getContentHeight();
        if (animProgress >= 1.0f && expanded) return fullHeight;
        if (animProgress <= 0.0f && !expanded) return HEADER_HEIGHT;
        return HEADER_HEIGHT + (int) ((fullHeight - HEADER_HEIGHT) * animProgress);
    }

    @Override
    public void setPosition(int x, int y) {
        super.setPosition(x, y);
        content.setPosition(x, y + HEADER_HEIGHT + HEADER_PAD);
    }

    @Override
    public void setSize(int width, int height) {
        super.setSize(width, height);
        content.setSize(width, content.getHeight());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        ColorScheme cs = ColorScheme.get();

        // Update content position in case parent moved us
        content.setPosition(x, y + HEADER_HEIGHT + HEADER_PAD);
        content.setSize(width, expanded ? content.getContentHeight() : 0);

        // Header
        String arrow = expanded ? "\u25BC " : "\u25B6 ";
        boolean headerHovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + HEADER_HEIGHT;
        int headerColor = headerHovered ? cs.textPrimary() : cs.accent();
        RenderUtils.drawText(g, arrow + title, x, y + 1, headerColor);

        // Subtle underline
        RenderUtils.drawHLine(g, x, y + HEADER_HEIGHT - 1, width,
                ColorScheme.withAlpha(cs.separator(), 80));

        // Content
        if (animProgress > 0.0f) {
            if (animProgress < 1.0f) {
                int contentH = getHeight() - HEADER_HEIGHT;
                RenderUtils.enableScissor(g, x, y + HEADER_HEIGHT, width, contentH);
                content.render(g, mouseX, mouseY, delta);
                RenderUtils.disableScissor(g);
            } else {
                content.render(g, mouseX, mouseY, delta);
            }
        }
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;

        // Header click toggles
        if (mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + HEADER_HEIGHT) {
            expanded = !expanded;
            return true;
        }

        // Forward to content if expanded
        if (expanded) {
            return content.onMouseClicked(mouseX, mouseY, button);
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (!visible || !expanded) return false;
        return content.onMouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!visible || !expanded) return false;
        return content.onMouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (!visible || !expanded) return false;
        return content.onMouseScrolled(mouseX, mouseY, hAmount, vAmount);
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible || !expanded) return false;
        return content.onKeyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (!visible || !expanded) return false;
        return content.onCharTyped(chr, modifiers);
    }

    @Override
    public void tick() {
        float target = expanded ? 1.0f : 0.0f;
        animProgress += (target - animProgress) * 0.3f;
        if (Math.abs(target - animProgress) < 0.01f) animProgress = target;
        if (expanded || animProgress > 0.0f) content.tick();
    }
}
