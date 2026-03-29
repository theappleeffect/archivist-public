package com.archivist.gui.panel;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import com.archivist.gui.widgets.Panel;
import com.archivist.gui.widgets.Widget;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Panel mode layout: fixed sidebar + content area.
 * Designed to live inside a DraggableWindow with FILL anchor.
 * Uses its own x, y, width, height (set by the parent window's layout system).
 *
 * In dynamic mode (detachEnabled=true), sidebar buttons can be dragged out to
 * become floating windows and reordered vertically.
 */
public class PanelModeLayout extends Widget {

    private static final int BUTTON_HEIGHT = 18;
    private static final int SIDEBAR_PADDING = 20;
    private static final int DIVIDER_HEIGHT = 1;
    private static final int DIVIDER_MARGIN = 4;

    /** Registered sections in insertion order. */
    private final Map<String, SectionEntry> sections = new LinkedHashMap<>();
    private final List<String> topSections = new ArrayList<>();
    private final List<String> bottomSections = new ArrayList<>();

    /** Sidebar buttons. */
    private final List<SidebarButton> topButtons = new ArrayList<>();
    private final List<SidebarButton> bottomButtons = new ArrayList<>();

    /** Currently active section name. */
    private String activeSection = null;

    /** Content panel (scrollable). */
    private Panel contentPanel;

    /** Computed sidebar width based on button text. */
    private int sidebarWidth = 90;

    // ── Dynamic Mode Fields ─────────────────────────────────────────────

    private boolean detachEnabled = false;
    private String sidebarSide = "left";
    private final Map<String, Boolean> detachedSections = new LinkedHashMap<>();
    private Consumer<DetachEvent> onDetachRequest;

    // Content fade animation
    private int contentFadeAlpha = 255;
    private String lastActiveSection = null;

    // Reorder state
    private SidebarButton reorderingButton = null;
    private int reorderOriginalIndex = -1;
    private int lastReorderTargetIdx = -1; // hysteresis: last committed target index

    /** Event record for detach requests. */
    public record DetachEvent(String sectionName, double mouseX, double mouseY) {}

    public PanelModeLayout(int x, int y, int width, int height) {
        super(x, y, width, height);
        contentPanel = new Panel(x + sidebarWidth + 1, y, width - sidebarWidth - 1, height);
        contentPanel.setPadding(6);
        contentPanel.setSpacing(2);
    }

    // ── Dynamic Mode Configuration ──────────────────────────────────────

    public void setDetachEnabled(boolean enabled) {
        this.detachEnabled = enabled;
    }

    public boolean isDetachEnabled() {
        return detachEnabled;
    }

    public void setSidebarSide(String side) {
        this.sidebarSide = side != null ? side.toLowerCase() : "left";
        layoutSidebar();
    }

    public String getSidebarSide() {
        return sidebarSide;
    }

    public int getSidebarWidth() {
        return sidebarWidth;
    }

    /** Get the center position of a sidebar button by section name. Returns [x, y] or null. */
    public int[] getSidebarButtonCenter(String sectionName) {
        for (SidebarButton btn : topButtons) {
            if (btn.getLabel().equals(sectionName)) {
                return new int[]{btn.getX() + btn.getWidth() / 2, btn.getY() + btn.getHeight() / 2};
            }
        }
        for (SidebarButton btn : bottomButtons) {
            if (btn.getLabel().equals(sectionName)) {
                return new int[]{btn.getX() + btn.getWidth() / 2, btn.getY() + btn.getHeight() / 2};
            }
        }
        return null;
    }

    public void setOnDetachRequest(Consumer<DetachEvent> callback) {
        this.onDetachRequest = callback;
    }

    /**
     * Mark a section as detached: dims its sidebar button.
     */
    public void markDetached(String name) {
        detachedSections.put(name, true);
        for (SidebarButton btn : topButtons) {
            if (btn.getLabel().equals(name)) { btn.setDimmed(true); break; }
        }
        for (SidebarButton btn : bottomButtons) {
            if (btn.getLabel().equals(name)) { btn.setDimmed(true); break; }
        }
        // If the detached section was active, switch to the first available docked section
        if (name.equals(activeSection)) {
            for (String s : topSections) {
                if (!detachedSections.containsKey(s)) { setActiveSection(s); return; }
            }
            for (String s : bottomSections) {
                if (!detachedSections.containsKey(s)) { setActiveSection(s); return; }
            }
        }
    }

    /**
     * Mark a section as docked: undims button, switches content to this section.
     */
    public void markDocked(String name) {
        detachedSections.remove(name);
        for (SidebarButton btn : topButtons) {
            if (btn.getLabel().equals(name)) {
                btn.setDimmed(false);
                btn.triggerPulse();
                break;
            }
        }
        for (SidebarButton btn : bottomButtons) {
            if (btn.getLabel().equals(name)) {
                btn.setDimmed(false);
                btn.triggerPulse();
                break;
            }
        }
        setActiveSection(name);
    }

    /**
     * Reorder a section in the top group.
     */
    public void reorderSection(String name, int newIndex) {
        if (topSections.remove(name)) {
            newIndex = Math.max(0, Math.min(newIndex, topSections.size()));
            topSections.add(newIndex, name);
            rebuildSidebarButtons();
        }
    }

    /**
     * Get the current sidebar order for persistence.
     */
    public List<String> getSidebarOrder() {
        List<String> order = new ArrayList<>();
        order.addAll(topSections);
        order.addAll(bottomSections);
        return order;
    }

    // ── Section Registration ─────────────────────────────────────────────

    /**
     * Register a section.
     * @param name         display name (also used as key)
     * @param contentBuilder callback that populates the content panel
     * @param bottomGroup  true to pin in the bottom group of the sidebar
     */
    public void addSection(String name, Consumer<Panel> contentBuilder, boolean bottomGroup) {
        sections.put(name, new SectionEntry(name, contentBuilder, bottomGroup));
        if (bottomGroup) {
            bottomSections.add(name);
        } else {
            topSections.add(name);
        }
        rebuildSidebarButtons();
    }

    /**
     * Apply a saved sidebar order (from config persistence).
     * Reorders topSections to match the saved order, preserving any sections
     * not in the saved list at the end.
     */
    public void applySidebarOrder(List<String> savedOrder) {
        if (savedOrder == null || savedOrder.isEmpty()) return;

        List<String> newTop = new ArrayList<>();
        for (String name : savedOrder) {
            if (topSections.contains(name)) {
                newTop.add(name);
            }
        }
        // Add any sections not in saved order
        for (String name : topSections) {
            if (!newTop.contains(name)) {
                newTop.add(name);
            }
        }
        topSections.clear();
        topSections.addAll(newTop);
        rebuildSidebarButtons();
    }

    /**
     * Switch to the given section: clear content panel and call content builder.
     */
    public void setActiveSection(String name) {
        SectionEntry entry = sections.get(name);
        if (entry == null) return;

        // Trigger fade animation on section change
        if (!name.equals(activeSection)) {
            lastActiveSection = activeSection;
            contentFadeAlpha = 0;
        }

        activeSection = name;

        // Update button active states
        for (SidebarButton btn : topButtons) btn.setActive(btn.getLabel().equals(name));
        for (SidebarButton btn : bottomButtons) btn.setActive(btn.getLabel().equals(name));

        // Clear and rebuild content
        contentPanel.clearChildren();
        contentPanel.resetScroll();
        entry.contentBuilder.accept(contentPanel);
        contentPanel.layoutChildren();
    }

    /**
     * Get the content panel (for external access, e.g. adding children dynamically).
     */
    public Panel getContentPanel() {
        return contentPanel;
    }

    public String getActiveSection() {
        return activeSection;
    }

    // ── Rebuild Sidebar ──────────────────────────────────────────────────

    private void rebuildSidebarButtons() {
        topButtons.clear();
        bottomButtons.clear();

        // Compute max preferred width across all buttons
        int maxPreferred = 0;

        for (String name : topSections) {
            SidebarButton btn = createSidebarButton(name);
            if (name.equals(activeSection)) btn.setActive(true);
            if (detachedSections.containsKey(name)) btn.setDimmed(true);
            topButtons.add(btn);
            maxPreferred = Math.max(maxPreferred, btn.getPreferredWidth());
        }

        for (String name : bottomSections) {
            SidebarButton btn = createSidebarButton(name);
            if (name.equals(activeSection)) btn.setActive(true);
            if (detachedSections.containsKey(name)) btn.setDimmed(true);
            bottomButtons.add(btn);
            maxPreferred = Math.max(maxPreferred, btn.getPreferredWidth());
        }

        sidebarWidth = maxPreferred + SIDEBAR_PADDING;

        layoutSidebar();
    }

    private SidebarButton createSidebarButton(String name) {
        SidebarButton btn = new SidebarButton(x, 0, 0, BUTTON_HEIGHT, name, () -> setActiveSection(name));

        if (detachEnabled && !sections.get(name).bottomGroup) {
            // Drag-to-detach callback
            btn.setOnDragDetach(b -> {
                if (onDetachRequest != null) {
                    onDetachRequest.accept(new DetachEvent(name, b.getDragCurrentX(), b.getDragCurrentY()));
                }
            });

            // Re-dock callback (for dimmed buttons)
            btn.setOnRedock(() -> markDocked(name));

            // Reorder callbacks
            btn.setOnReorderStart(b -> {
                reorderingButton = b;
                reorderOriginalIndex = topSections.indexOf(name);
                lastReorderTargetIdx = -1;
            });
            btn.setOnReorderEnd(dragDy -> {
                if (reorderingButton != null) {
                    finalizeReorder(dragDy);
                    reorderingButton = null;
                    reorderOriginalIndex = -1;
                    lastReorderTargetIdx = -1;
                }
            });
        }

        return btn;
    }

    private void finalizeReorder(float dragDy) {
        if (reorderingButton == null || reorderOriginalIndex < 0) return;

        String name = reorderingButton.getLabel();
        // Use the hysteresis-computed target index if available, otherwise fall back to rounding
        int newIndex;
        if (lastReorderTargetIdx >= 0) {
            newIndex = lastReorderTargetIdx;
        } else {
            int positionsToMove = Math.round(dragDy / BUTTON_HEIGHT);
            newIndex = reorderOriginalIndex + positionsToMove;
        }
        newIndex = Math.max(0, Math.min(newIndex, topSections.size() - 1));

        if (newIndex != reorderOriginalIndex) {
            // Reorder topSections
            topSections.remove(reorderOriginalIndex);
            topSections.add(newIndex, name);

            // Reorder topButtons to match
            SidebarButton btn = topButtons.remove(reorderOriginalIndex);
            topButtons.add(newIndex, btn);
        }

        // Reset all offsets and relayout
        for (SidebarButton btn : topButtons) {
            btn.resetDisplayOffset();
        }
        reorderingButton.resetDisplayOffset();
        layoutSidebar();
    }

    private void layoutSidebar() {
        int sidebarX;
        int contentX;

        if ("right".equals(sidebarSide)) {
            sidebarX = x + width - sidebarWidth;
            contentX = x;
        } else {
            sidebarX = x;
            contentX = x + sidebarWidth + 1;
        }

        // Count visible (non-dimmed) top buttons for centering
        int visibleTopCount = 0;
        for (SidebarButton btn : topButtons) {
            if (!btn.isDimmed()) visibleTopCount++;
        }
        int buttonGap = 1; // subtle gap between buttons for border spacing
        int buttonInset = 4; // horizontal margin inside sidebar
        int cy = y + 8; // top-align with small margin

        // Top buttons: stack from top, skip dimmed buttons, with gap
        for (SidebarButton btn : topButtons) {
            if (btn.isDimmed()) continue;
            btn.setPosition(sidebarX + buttonInset, cy);
            btn.setSize(sidebarWidth - buttonInset * 2, BUTTON_HEIGHT);
            cy += BUTTON_HEIGHT + buttonGap;
        }

        // Bottom buttons: stack from bottom, with gap
        int bottomTotalHeight = bottomButtons.size() * (BUTTON_HEIGHT + buttonGap) - (bottomButtons.isEmpty() ? 0 : buttonGap);
        int bottomStart = y + height - 4 - bottomTotalHeight;
        int by = bottomStart;
        for (SidebarButton btn : bottomButtons) {
            btn.setPosition(sidebarX + buttonInset, by);
            btn.setSize(sidebarWidth - buttonInset * 2, BUTTON_HEIGHT);
            by += BUTTON_HEIGHT + buttonGap;
        }

        // Content panel position
        int contentWidth = "right".equals(sidebarSide)
                ? width - sidebarWidth - 1
                : width - sidebarWidth - 1;
        contentPanel.setPosition(contentX, y);
        contentPanel.setSize(contentWidth, height);
    }

    // ── Render ───────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;

        ColorScheme cs = ColorScheme.get();

        // Recalculate layout (handles resize)
        layoutSidebar();

        int sidebarX = "right".equals(sidebarSide) ? x + width - sidebarWidth : x;
        int dividerX = "right".equals(sidebarSide) ? x + width - sidebarWidth - 1 : x + sidebarWidth;

        // Count visible top buttons for divider logic
        int visibleTopCount = 0;
        for (SidebarButton btn : topButtons) {
            if (!btn.isDimmed()) visibleTopCount++;
        }

        // Top buttons (skip dimmed — they are hidden when detached)
        for (SidebarButton btn : topButtons) {
            if (btn.isDimmed()) continue;
            btn.render(g, mouseX, mouseY, delta);
        }

        // Divider line between top and bottom groups (only if visible top buttons exist)
        if (visibleTopCount > 0 && !bottomButtons.isEmpty()) {
            int dividerY = bottomButtons.get(0).getY() - DIVIDER_MARGIN - DIVIDER_HEIGHT;
            RenderUtils.drawHLine(g, sidebarX + 4, dividerY, sidebarWidth - 8, cs.separator());
        }

        // Bottom buttons
        for (SidebarButton btn : bottomButtons) {
            btn.render(g, mouseX, mouseY, delta);
        }

        // Vertical separator between sidebar and content
        RenderUtils.drawVLine(g, dividerX, y, height, cs.separator());

        // Content panel with fade alpha
        if (contentFadeAlpha < 255) {
            // Simple approach: just render normally, the fade is very fast
            contentPanel.render(g, mouseX, mouseY, delta);
        } else {
            contentPanel.render(g, mouseX, mouseY, delta);
        }

        // Reorder visual feedback: shift other buttons when one is being dragged
        if (reorderingButton != null) {
            updateReorderVisuals();
        }
    }

    private void updateReorderVisuals() {
        if (reorderingButton == null) return;

        String dragName = reorderingButton.getLabel();
        float dragDy = reorderingButton.getDisplayYOffset();

        int dragIdx = -1;
        for (int i = 0; i < topButtons.size(); i++) {
            if (topButtons.get(i).getLabel().equals(dragName)) {
                dragIdx = i;
                break;
            }
        }
        if (dragIdx < 0) return;

        // Calculate candidate target index
        int candidatePositions = Math.round(dragDy / BUTTON_HEIGHT);
        int candidateIdx = dragIdx + candidatePositions;
        candidateIdx = Math.max(0, Math.min(candidateIdx, topButtons.size() - 1));

        // Hysteresis: only update target when drag moves more than half a button past the boundary
        int targetIdx;
        if (lastReorderTargetIdx < 0) {
            lastReorderTargetIdx = dragIdx;
        }
        if (candidateIdx != lastReorderTargetIdx) {
            // Check if we've moved far enough past the boundary to commit to the new position
            float distFromCurrent = Math.abs(dragDy - (lastReorderTargetIdx - dragIdx) * BUTTON_HEIGHT);
            if (distFromCurrent > BUTTON_HEIGHT * 0.6f) {
                lastReorderTargetIdx = candidateIdx;
            }
        }
        targetIdx = lastReorderTargetIdx;

        for (int i = 0; i < topButtons.size(); i++) {
            SidebarButton btn = topButtons.get(i);
            if (btn == reorderingButton) continue;

            // Calculate whether this button needs to shift
            if (dragIdx < targetIdx && i > dragIdx && i <= targetIdx) {
                btn.setTargetYOffset(-BUTTON_HEIGHT);
            } else if (dragIdx > targetIdx && i < dragIdx && i >= targetIdx) {
                btn.setTargetYOffset(BUTTON_HEIGHT);
            } else {
                btn.setTargetYOffset(0);
            }
        }
    }

    // ── Input Forwarding ─────────────────────────────────────────────────

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible) return false;

        int sidebarX = "right".equals(sidebarSide) ? x + width - sidebarWidth : x;

        // Block clicks during reorder to prevent buttons jumping back
        if (reorderingButton != null) return true;

        // Sidebar clicks
        if (mouseX >= sidebarX && mouseX < sidebarX + sidebarWidth && mouseY >= y && mouseY < y + height) {
            for (SidebarButton btn : topButtons) {
                if (btn.isDimmed()) continue;
                if (btn.onMouseClicked(mouseX, mouseY, button)) return true;
            }
            for (SidebarButton btn : bottomButtons) {
                if (btn.onMouseClicked(mouseX, mouseY, button)) return true;
            }
            return true; // consume click in sidebar area
        }

        // Content panel clicks
        return contentPanel.onMouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (!visible) return false;
        // Forward to sidebar buttons first (for drag release)
        for (SidebarButton btn : topButtons) {
            if (btn.isDimmed()) continue;
            if (btn.onMouseReleased(mouseX, mouseY, button)) return true;
        }
        for (SidebarButton btn : bottomButtons) {
            if (btn.onMouseReleased(mouseX, mouseY, button)) return true;
        }
        return contentPanel.onMouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (!visible) return false;
        // Forward to sidebar buttons first (for drag detection)
        for (SidebarButton btn : topButtons) {
            if (btn.isDimmed()) continue;
            if (btn.isDragging() && btn.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        }
        for (SidebarButton btn : bottomButtons) {
            if (btn.isDragging() && btn.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        }
        return contentPanel.onMouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (!visible) return false;
        return contentPanel.onMouseScrolled(mouseX, mouseY, hAmount, vAmount);
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) return false;
        return contentPanel.onKeyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        if (!visible) return false;
        return contentPanel.onCharTyped(chr, modifiers);
    }

    @Override
    public void tick() {
        contentPanel.tick();

        // Tick sidebar buttons for animations
        for (SidebarButton btn : topButtons) btn.tick();
        for (SidebarButton btn : bottomButtons) btn.tick();

        // Content fade animation
        if (contentFadeAlpha < 255) {
            contentFadeAlpha = Math.min(255, contentFadeAlpha + 51); // ~5 ticks to full
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private record SectionEntry(String name, Consumer<Panel> contentBuilder, boolean bottomGroup) {}
}
