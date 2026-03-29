package com.archivist.gui.widgets;

import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;

/**
 * Value slider widget with min/max/step. Shows current value as text.
 */
public class Slider extends Widget {

    private String label;
    private double min, max, step;
    private double value;
    private String suffix;
    private Consumer<Double> onChange;
    private boolean dragging = false;

    public Slider(int x, int y, int width, String label, double min, double max, double step,
                  double value, String suffix, Consumer<Double> onChange) {
        super(x, y, width, 20);
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = value;
        this.suffix = suffix != null ? suffix : "";
        this.onChange = onChange;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        updateHover(mouseX, mouseY);
        ColorScheme cs = ColorScheme.get();

        String valText = formatValue() + suffix;
        String display = label + ": " + valText;
        RenderUtils.drawText(g, display, x, y, cs.textPrimary());

        int trackY = y + RenderUtils.scaledFontHeight() + 2;
        int trackH = 4;
        int trackW = width;

        RenderUtils.drawRect(g, x, trackY, trackW, trackH, cs.scrollbarTrack());

        float ratio = (float) ((value - min) / (max - min));
        int fillW = (int) (trackW * ratio);
        RenderUtils.drawRect(g, x, trackY, fillW, trackH, ColorScheme.withAlpha(cs.accent(), 100));

        int thumbX = x + fillW - 3;
        int thumbW = 6;
        int thumbY = trackY - 1;
        int thumbH = trackH + 2;
        RenderUtils.drawRect(g, thumbX, thumbY, thumbW, thumbH,
                dragging || hovered ? cs.accent() : cs.scrollbarThumb());
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || button != 0) return false;
        int trackY = y + RenderUtils.scaledFontHeight() + 2;
        if (mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < trackY + 8) {
            dragging = true;
            updateValue(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging) {
            updateValue(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        return false;
    }

    private void updateValue(double mouseX) {
        float ratio = (float) ((mouseX - x) / width);
        ratio = Math.max(0, Math.min(1, ratio));
        double raw = min + (max - min) * ratio;
        if (step > 0) {
            raw = Math.round(raw / step) * step;
        }
        value = Math.max(min, Math.min(max, raw));
        if (onChange != null) onChange.accept(value);
    }

    private String formatValue() {
        if (step >= 1) return String.valueOf((int) value);
        return String.format("%.1f", value);
    }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = Math.max(min, Math.min(max, value)); }
}
