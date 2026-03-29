package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import com.archivist.gui.render.ThemeGenerator;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * Square color picker widget. X axis = hue (0-360), Y axis = saturation (1→0 top to bottom).
 * A lightness strip below allows adjusting brightness. Glowing border pulses via tick.
 * Renders using cached block grid for performance (~500 fills instead of ~5000).
 */
public class ColorWheelWidget extends Widget {

    private static final int STRIP_HEIGHT = 8;
    private static final int STRIP_GAP = 2;
    private static final int GLOW_SIZE = 2;
    private static final int BLOCK = 3; // render in 3x3 pixel blocks

    private float selectedHue = 0.55f;
    private float selectedSat = 0.8f;
    private float selectedLight = 0.5f;

    private boolean draggingSquare = false;
    private boolean draggingStrip = false;
    private int glowTick = 0;

    // Cached grid — only regenerated when lightness changes
    private int[] cachedGrid;
    private float cachedLight = -1;
    private int cachedGridCols;
    private int cachedGridRows;

    private final String label;
    private final Consumer<Integer> onChange;

    public ColorWheelWidget(int x, int y, int size, String label, Consumer<Integer> onChange) {
        super(x, y, size, size + STRIP_HEIGHT + STRIP_GAP + RenderUtils.scaledFontHeight() + 12);
        this.label = label;
        this.onChange = onChange;
    }

    public void setColor(int argb) {
        float[] hsl = ThemeGenerator.rgbToHsl(argb);
        this.selectedHue = hsl[0];
        this.selectedSat = hsl[1];
        this.selectedLight = hsl[2];
        cachedLight = -1; // force regenerate
    }

    public int getColor() {
        return ThemeGenerator.hslToArgb(selectedHue, selectedSat, selectedLight, 1.0f);
    }

    @Override
    public void tick() { glowTick++; }

    private int squareSize() { return width; }

    private void ensureCache(int sz) {
        if (cachedLight == selectedLight && cachedGrid != null) return;
        int cols = (sz + BLOCK - 1) / BLOCK;
        int rows = cols;
        cachedGrid = new int[cols * rows];
        for (int gy = 0; gy < rows; gy++) {
            float sat = 1.0f - (float) gy / (rows - 1);
            for (int gx = 0; gx < cols; gx++) {
                float hue = (float) gx / (cols - 1);
                cachedGrid[gy * cols + gx] = ThemeGenerator.hslToArgb(hue, sat, selectedLight, 1.0f);
            }
        }
        cachedGridCols = cols;
        cachedGridRows = rows;
        cachedLight = selectedLight;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        int sz = squareSize();
        ColorScheme cs = ColorScheme.get();

        // ── Label ──
        int labelY = y;
        RenderUtils.drawText(g, label, x + (sz - RenderUtils.scaledTextWidth(label)) / 2, labelY, cs.textPrimary());
        int squareY = labelY + RenderUtils.scaledFontHeight() + 2;

        // ── Glow border (pulsing) ──
        float pulse = (float) (Math.sin(glowTick * 0.15) * 0.3 + 0.7);
        int glowAlpha = (int) (pulse * 120);
        for (int i = 1; i <= GLOW_SIZE; i++) {
            int gc = ColorScheme.withAlpha(getColor(), glowAlpha / i);
            RenderUtils.drawBorder(g, x - i, squareY - i, sz + i * 2, sz + i * 2, gc);
        }

        // ── Hue-Saturation square (cached block grid) ──
        ensureCache(sz);
        for (int gy = 0; gy < cachedGridRows; gy++) {
            int py = gy * BLOCK;
            int ph = Math.min(BLOCK, sz - py);
            for (int gx = 0; gx < cachedGridCols; gx++) {
                int px = gx * BLOCK;
                int pw = Math.min(BLOCK, sz - px);
                g.fill(x + px, squareY + py, x + px + pw, squareY + py + ph,
                        cachedGrid[gy * cachedGridCols + gx]);
            }
        }

        // ── Selection crosshair ──
        int selX = x + (int) (selectedHue * (sz - 1));
        int selY = squareY + (int) ((1.0f - selectedSat) * (sz - 1));
        int crossColor = selectedLight > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
        g.fill(selX - 3, selY, selX + 4, selY + 1, crossColor);
        g.fill(selX, selY - 3, selX + 1, selY + 4, crossColor);

        // ── Lightness strip (block-based) ──
        int stripY = squareY + sz + STRIP_GAP;
        int stripCols = (sz + BLOCK - 1) / BLOCK;
        for (int gx = 0; gx < stripCols; gx++) {
            int px = gx * BLOCK;
            int pw = Math.min(BLOCK, sz - px);
            float l = (float) gx / (stripCols - 1);
            int color = ThemeGenerator.hslToArgb(selectedHue, selectedSat, l, 1.0f);
            g.fill(x + px, stripY, x + px + pw, stripY + STRIP_HEIGHT, color);
        }

        // ── Lightness indicator ──
        int lightX = x + (int) (selectedLight * (sz - 1));
        g.fill(lightX - 1, stripY - 1, lightX + 2, stripY + STRIP_HEIGHT + 1, 0xFFFFFFFF);
        g.fill(lightX, stripY, lightX + 1, stripY + STRIP_HEIGHT, 0xFF000000);

        // ── Hex value ──
        String hex = String.format("#%06X", getColor() & 0xFFFFFF);
        int hexY = stripY + STRIP_HEIGHT + 2;
        RenderUtils.drawText(g, hex, x + (sz - RenderUtils.scaledTextWidth(hex)) / 2, hexY, cs.textSecondary());
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !visible) return false;
        int sz = squareSize();
        int squareY = y + RenderUtils.scaledFontHeight() + 2;
        int stripY = squareY + sz + STRIP_GAP;

        if (mouseX >= x && mouseX < x + sz && mouseY >= squareY && mouseY < squareY + sz) {
            draggingSquare = true;
            updateFromSquare(mouseX, mouseY, squareY, sz);
            return true;
        }
        if (mouseX >= x && mouseX < x + sz && mouseY >= stripY && mouseY < stripY + STRIP_HEIGHT) {
            draggingStrip = true;
            updateFromStrip(mouseX, sz);
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button != 0) return false;
        int sz = squareSize();
        int squareY = y + RenderUtils.scaledFontHeight() + 2;

        if (draggingSquare) {
            updateFromSquare(mouseX, mouseY, squareY, sz);
            return true;
        }
        if (draggingStrip) {
            updateFromStrip(mouseX, sz);
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (draggingSquare || draggingStrip) {
            draggingSquare = false;
            draggingStrip = false;
            return true;
        }
        return false;
    }

    private void updateFromSquare(double mouseX, double mouseY, int squareY, int sz) {
        selectedHue = Math.max(0, Math.min(1, (float) (mouseX - x) / (sz - 1)));
        selectedSat = Math.max(0, Math.min(1, 1.0f - (float) (mouseY - squareY) / (sz - 1)));
        fireChange();
    }

    private void updateFromStrip(double mouseX, int sz) {
        float newLight = Math.max(0, Math.min(1, (float) (mouseX - x) / (sz - 1)));
        if (newLight != selectedLight) {
            selectedLight = newLight;
            cachedLight = -1; // invalidate cache — lightness changed
        }
        fireChange();
    }

    private void fireChange() {
        if (onChange != null) onChange.accept(getColor());
    }
}
