package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages character-level text selection across all widgets, supporting multi-line selection.
 * Hooks into RenderUtils.drawScaledText — each text draw call registers a region and
 * checks for selection overlap to render yellow highlights.
 */
public class TextSelectionManager {

    private static final int DRAG_THRESHOLD = 2;
    private static final int X_TOLERANCE = 20;

    private static boolean enabled = true;

    private static int currentMouseX, currentMouseY;
    private static boolean mouseDown = false;
    private static boolean dragged = false;
    private static double anchorX, anchorY;

    private static final List<TextRegion> regions = new ArrayList<>();
    private static int frameRegionIndex = 0;

    private static boolean hasSelection = false;
    private static int anchorRegionIdx = -1;
    private static int anchorChar = -1;
    private static List<SelectedLine> selectedLines = new ArrayList<>();

    private record TextRegion(String text, int screenX, int screenY, float scale, int index) {}
    private record SelectedLine(String text, int fromChar, int toChar) {}

    public static void setEnabled(boolean e) { enabled = e; }

    public static void beginFrame() {
        regions.clear();
        frameRegionIndex = 0;
    }

    public static void updateMouse(int mx, int my) {
        currentMouseX = mx;
        currentMouseY = my;
    }

    public static void onMousePressed(double mx, double my, int button) {
        if (button != 0) return;
        hasSelection = false;
        selectedLines.clear();
        anchorRegionIdx = -1;
        anchorX = mx;
        anchorY = my;
        mouseDown = true;
        dragged = false;
    }

    public static void onMouseDragged(double mx, double my) {
        if (!mouseDown) return;
        double dx = mx - anchorX;
        double dy = my - anchorY;
        if (!dragged && (dx * dx + dy * dy) >= DRAG_THRESHOLD * DRAG_THRESHOLD) {
            dragged = true;
        }
    }

    public static void onMouseReleased() {
        mouseDown = false;
        if (!dragged) {
            hasSelection = false;
            selectedLines.clear();
        }
    }

    public static String getSelectedText() {
        if (!hasSelection || selectedLines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selectedLines.size(); i++) {
            SelectedLine sl = selectedLines.get(i);
            if (sl.fromChar >= sl.toChar || sl.fromChar < 0 || sl.toChar > sl.text.length()) continue;
            if (!sb.isEmpty()) sb.append('\n');
            sb.append(sl.text, sl.fromChar, sl.toChar);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    public static boolean hasActiveSelection() {
        String sel = getSelectedText();
        return sel != null && !sel.isEmpty();
    }

    public static void clearSelection() {
        hasSelection = false;
        selectedLines.clear();
        mouseDown = false;
        dragged = false;
        anchorRegionIdx = -1;
    }

    private static boolean programmaticSelect = false;
    private static int programmaticFrames = 0;

    /**
     * Programmatically select all text within a rectangular region.
     * Sets anchor at top-left, cursor at bottom-right — the render loop
     * will highlight all text within these bounds on the next frame.
     */
    public static void selectAll(int regionX, int regionY, int regionW, int regionH) {
        clearSelection();
        anchorX = regionX;
        anchorY = regionY;
        currentMouseX = regionX + regionW;
        currentMouseY = regionY + regionH;
        mouseDown = true;
        dragged = true;
        programmaticSelect = true;
        programmaticFrames = 0;
    }

    /**
     * Called each render frame. Finalizes a programmatic selection after
     * one render pass so it doesn't interfere with mouse-drag selection.
     */
    public static void finalizeSelection() {
        if (!programmaticSelect) return;
        programmaticFrames++;
        if (programmaticFrames >= 2 && mouseDown && dragged) {
            mouseDown = false;
            hasSelection = true;
            programmaticSelect = false;
        }
    }

    /**
     * Called by RenderUtils text drawing methods AFTER pose push/translate/scale,
     * BEFORE g.drawString. Registers the region and draws highlights if selected.
     */
    public static void checkAndHighlight(GuiGraphics g, String text, int screenX, int screenY, float scale) {
        if (!enabled || text == null || text.isEmpty()) return;

        int idx = frameRegionIndex++;
        regions.add(new TextRegion(text, screenX, screenY, scale, idx));

        if (!mouseDown && !hasSelection) return;

        var font = Minecraft.getInstance().font;
        int textW = (int) (font.width(text) * scale);
        int textH = (int) (font.lineHeight * scale);

        // ── Active dragging ──
        if (mouseDown && dragged) {
            if (anchorRegionIdx == -1) {
                boolean anchorHit = anchorX >= screenX && anchorX < screenX + textW
                        && anchorY >= screenY && anchorY < screenY + textH;
                if (anchorHit) {
                    anchorRegionIdx = idx;
                    anchorChar = charIndexAtScreenX(text, screenX, anchorX, scale, font);
                }
            }

            if (anchorRegionIdx == -1) return;

            TextRegion anchorRegion = null;
            for (TextRegion r : regions) {
                if (r.index == anchorRegionIdx) { anchorRegion = r; break; }
            }
            if (anchorRegion == null) return;

            if (Math.abs(screenX - anchorRegion.screenX) > X_TOLERANCE) return;

            int anchorY_ = anchorRegion.screenY;
            int cursorY_ = currentMouseY;
            int topY = Math.min(anchorY_, cursorY_);
            int bottomY = Math.max(anchorY_, cursorY_);

            if (screenY + textH <= topY || screenY > bottomY) return;

            boolean isAnchorLine = (idx == anchorRegionIdx);
            boolean isCursorLine = (currentMouseY >= screenY && currentMouseY < screenY + textH);
            boolean selectingDown = anchorY_ <= cursorY_;

            int from, to;
            if (isAnchorLine && isCursorLine) {
                int curChar = charIndexAtScreenX(text, screenX, currentMouseX, scale, font);
                from = Math.min(anchorChar, curChar);
                to = Math.max(anchorChar, curChar);
            } else if (isAnchorLine) {
                if (selectingDown) {
                    from = anchorChar;
                    to = text.length();
                } else {
                    from = 0;
                    to = anchorChar;
                }
            } else if (isCursorLine) {
                int curChar = charIndexAtScreenX(text, screenX, currentMouseX, scale, font);
                if (selectingDown) {
                    from = 0;
                    to = curChar;
                } else {
                    from = curChar;
                    to = text.length();
                }
            } else {
                from = 0;
                to = text.length();
            }

            if (from < to) {
                hasSelection = true;
                drawHighlight(g, text, from, to, font);
                updateSelectedLine(text, from, to, screenY);
            }
            return;
        }

        // ── Finalized selection (not dragging, hasSelection) ──
        if (hasSelection && !selectedLines.isEmpty()) {
            for (SelectedLine sl : selectedLines) {
                if (sl.text.equals(text) && sl.fromChar < sl.toChar) {
                    drawHighlight(g, text, sl.fromChar, sl.toChar, font);
                    return;
                }
            }
        }
    }

    private static void updateSelectedLine(String text, int from, int to, int screenY) {
        for (int i = 0; i < selectedLines.size(); i++) {
            if (selectedLines.get(i).text.equals(text)) {
                selectedLines.set(i, new SelectedLine(text, from, to));
                return;
            }
        }
        int insertIdx = 0;
        for (int i = 0; i < selectedLines.size(); i++) {
            insertIdx = i + 1;
        }
        selectedLines.add(insertIdx, new SelectedLine(text, from, to));
    }

    private static void drawHighlight(GuiGraphics g, String text, int from, int to, net.minecraft.client.gui.Font font) {
        from = Math.max(0, Math.min(from, text.length()));
        to = Math.max(0, Math.min(to, text.length()));
        if (from >= to) return;

        int x1 = font.width(text.substring(0, from));
        int x2 = font.width(text.substring(0, to));
        int h = font.lineHeight;
        g.fill(x1, 0, x2, h, ColorScheme.withAlpha(ColorScheme.get().accent(), 0x80));
    }

    private static int charIndexAtScreenX(String text, int regionX, double mouseScreenX, float scale, net.minecraft.client.gui.Font font) {
        double relX = (mouseScreenX - regionX) / scale;
        if (relX <= 0) return 0;

        for (int i = 1; i <= text.length(); i++) {
            float charEndX = font.width(text.substring(0, i));
            if (relX < charEndX) {
                float charStartX = font.width(text.substring(0, i - 1));
                return relX < (charStartX + charEndX) / 2.0 ? i - 1 : i;
            }
        }
        return text.length();
    }
}
