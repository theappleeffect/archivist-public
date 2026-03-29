package com.archivist.gui.widgets;

import com.archivist.config.ConfigManager;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RainbowTheme;
import com.archivist.gui.render.RenderUtils;
import com.archivist.gui.render.ThemeGenerator;
import com.archivist.gui.render.ThemeManager;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.GuiGraphics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Theme creator panel with 3 color wheels (Primary/Secondary/Misc),
 * transparency sliders, name field, and save button.
 * All changes apply live as colors are picked.
 */
public class ThemeCreatorPanel extends Widget {

    private static final int WHEEL_SIZE = 70;
    private static final int WHEEL_GAP = 8;

    private final ColorWheelWidget primaryWheel;
    private final ColorWheelWidget secondaryWheel;
    private final ColorWheelWidget miscWheel;
    private final Slider bgAlphaSlider;
    private final Slider titleBarAlphaSlider;
    private final Slider overlayAlphaSlider;
    private final TextField nameField;
    private final Button saveButton;
    private final Label contrastLabel;

    private int bgAlpha = 0xBB;
    private int titleBarAlpha = 0xDD;
    private int overlayAlpha = 0x40;

    private boolean needsRebuild = false;
    private boolean customModified = false;

    public ThemeCreatorPanel(int x, int y, int width) {
        super(x, y, width, 450);

        primaryWheel = new ColorWheelWidget(0, 0, WHEEL_SIZE, "Primary", c -> applyLive());
        secondaryWheel = new ColorWheelWidget(0, 0, WHEEL_SIZE, "Secondary", c -> applyLive());
        miscWheel = new ColorWheelWidget(0, 0, WHEEL_SIZE, "Misc", c -> applyLive());

        // Default colors
        primaryWheel.setColor(0xFF40C0E0);
        secondaryWheel.setColor(0xFF0A1020);
        miscWheel.setColor(0xFFE0E0E0);

        bgAlphaSlider = new Slider(0, 0, width - 8, "BG Transparency", 0, 255, 1,
                bgAlpha, "", v -> { bgAlpha = v.intValue(); applyLive(); });
        titleBarAlphaSlider = new Slider(0, 0, width - 8, "Title Bar Transparency", 0, 255, 1,
                titleBarAlpha, "", v -> { titleBarAlpha = v.intValue(); applyLive(); });
        overlayAlphaSlider = new Slider(0, 0, width - 8, "Overlay Transparency", 0, 255, 1,
                overlayAlpha, "", v -> { overlayAlpha = v.intValue(); applyLive(); });

        // Sync slider defaults from active RainbowTheme if applicable
        if (ColorScheme.get() instanceof RainbowTheme rt) {
            bgAlpha = rt.getBgAlpha();
            titleBarAlpha = rt.getTitleBarAlpha();
            overlayAlpha = rt.getOverlayAlpha();
            bgAlphaSlider.setValue(bgAlpha);
            titleBarAlphaSlider.setValue(titleBarAlpha);
            overlayAlphaSlider.setValue(overlayAlpha);
        }

        nameField = new TextField(0, 0, width - 8, "Theme name...");
        saveButton = new Button(0, 0, width - 8, 14, "Save Theme", this::saveTheme);
        contrastLabel = new Label(0, 0, width - 8, "", ColorScheme.get().textSecondary());
    }

    private void applyLive() {
        if (ColorScheme.get() instanceof RainbowTheme rt) {
            rt.setAlphas(bgAlpha, titleBarAlpha, overlayAlpha);
            needsRebuild = true;
            return;
        }
        ColorScheme generated = ThemeGenerator.generate(
                "Custom",
                primaryWheel.getColor(),
                secondaryWheel.getColor(),
                miscWheel.getColor(),
                bgAlpha, titleBarAlpha, overlayAlpha);
        ColorScheme.setActive(generated);
        updateContrastLabel();
        needsRebuild = true;
        customModified = true;
    }

    private void updateContrastLabel() {
        int text = miscWheel.getColor();
        int bg = secondaryWheel.getColor();
        double ratio = ColorScheme.contrastRatio(text, bg);
        if (ratio < 4.5) {
            contrastLabel.setText("\u26A0 Low contrast: " + String.format("%.1f", ratio) + ":1 (need 4.5:1)");
            contrastLabel.setColor(ColorScheme.get().eventError());
        } else {
            contrastLabel.setText("\u2713 Contrast: " + String.format("%.1f", ratio) + ":1");
            contrastLabel.setColor(ColorScheme.get().eventConnect());
        }
    }

    private void saveTheme() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) name = "Custom_" + System.currentTimeMillis();

        // Build a simplified JSON
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("accent", String.format("#%06X", primaryWheel.getColor() & 0xFFFFFF));
        root.addProperty("background", String.format("#%06X", secondaryWheel.getColor() & 0xFFFFFF));
        root.addProperty("text", String.format("#%06X", miscWheel.getColor() & 0xFFFFFF));
        root.addProperty("bgAlpha", bgAlpha);
        root.addProperty("titleBarAlpha", titleBarAlpha);
        root.addProperty("overlayAlpha", overlayAlpha);

        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("archivist").resolve("themes");
            Files.createDirectories(dir);
            String safeName = name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
            Path file = dir.resolve(safeName + ".json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);

            // Reload themes and activate
            ThemeManager.getInstance().load();
            ColorScheme theme = ThemeManager.getInstance().getTheme(name);
            if (theme != null) {
                ColorScheme.setActive(theme);
                var config = com.archivist.ArchivistMod.getInstance().getConfig();
                config.activeTheme = name;
                ConfigManager.save(config);
            }
            customModified = false;
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.warn("Failed to save theme", e);
        }
    }

    /** Returns true and resets if the parent screen should rebuild its GUI. */
    public boolean consumeRebuild() {
        if (needsRebuild) { needsRebuild = false; return true; }
        return false;
    }

    /** Returns true if the user has modified colors/sliders without saving. */
    public boolean isCustomModified() { return customModified; }

    /** Clears the custom-modified flag (e.g. when selecting an existing theme). */
    public void clearCustomModified() { customModified = false; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!visible) return;
        int cy = y;

        // Stack wheels vertically, centered
        int wheelX = x + (width - WHEEL_SIZE) / 2;

        primaryWheel.setPosition(wheelX, cy);
        primaryWheel.render(g, mouseX, mouseY, delta);
        cy += primaryWheel.getHeight() + WHEEL_GAP;

        secondaryWheel.setPosition(wheelX, cy);
        secondaryWheel.render(g, mouseX, mouseY, delta);
        cy += secondaryWheel.getHeight() + WHEEL_GAP;

        miscWheel.setPosition(wheelX, cy);
        miscWheel.render(g, mouseX, mouseY, delta);
        cy += miscWheel.getHeight() + 6;

        // Contrast label
        contrastLabel.setPosition(x + 4, cy);
        contrastLabel.render(g, mouseX, mouseY, delta);
        cy += 12;

        // Transparency sliders
        bgAlphaSlider.setPosition(x + 4, cy);
        bgAlphaSlider.render(g, mouseX, mouseY, delta);
        cy += bgAlphaSlider.getHeight() + 2;

        titleBarAlphaSlider.setPosition(x + 4, cy);
        titleBarAlphaSlider.render(g, mouseX, mouseY, delta);
        cy += titleBarAlphaSlider.getHeight() + 2;

        overlayAlphaSlider.setPosition(x + 4, cy);
        overlayAlphaSlider.render(g, mouseX, mouseY, delta);
        cy += overlayAlphaSlider.getHeight() + 6;

        // Name field + save button
        nameField.setPosition(x + 4, cy);
        nameField.setSize(width - 8, nameField.getHeight());
        nameField.render(g, mouseX, mouseY, delta);
        cy += nameField.getHeight() + 4;

        saveButton.setPosition(x + 4, cy);
        saveButton.setSize(width - 8, saveButton.getHeight());
        saveButton.render(g, mouseX, mouseY, delta);
        cy += saveButton.getHeight() + 2;

        this.height = cy - y;
    }

    @Override
    public void tick() {
        primaryWheel.tick();
        secondaryWheel.tick();
        miscWheel.tick();
        nameField.tick();
    }

    @Override
    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (primaryWheel.onMouseClicked(mouseX, mouseY, button)) return true;
        if (secondaryWheel.onMouseClicked(mouseX, mouseY, button)) return true;
        if (miscWheel.onMouseClicked(mouseX, mouseY, button)) return true;
        if (bgAlphaSlider.onMouseClicked(mouseX, mouseY, button)) return true;
        if (titleBarAlphaSlider.onMouseClicked(mouseX, mouseY, button)) return true;
        if (overlayAlphaSlider.onMouseClicked(mouseX, mouseY, button)) return true;
        if (nameField.onMouseClicked(mouseX, mouseY, button)) return true;
        if (saveButton.onMouseClicked(mouseX, mouseY, button)) return true;
        return false;
    }

    @Override
    public boolean onMouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (primaryWheel.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        if (secondaryWheel.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        if (miscWheel.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        if (bgAlphaSlider.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        if (titleBarAlphaSlider.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        if (overlayAlphaSlider.onMouseDragged(mouseX, mouseY, button, dx, dy)) return true;
        return false;
    }

    @Override
    public boolean onMouseReleased(double mouseX, double mouseY, int button) {
        if (primaryWheel.onMouseReleased(mouseX, mouseY, button)) return true;
        if (secondaryWheel.onMouseReleased(mouseX, mouseY, button)) return true;
        if (miscWheel.onMouseReleased(mouseX, mouseY, button)) return true;
        if (bgAlphaSlider.onMouseReleased(mouseX, mouseY, button)) return true;
        if (titleBarAlphaSlider.onMouseReleased(mouseX, mouseY, button)) return true;
        if (overlayAlphaSlider.onMouseReleased(mouseX, mouseY, button)) return true;
        return false;
    }

    @Override
    public boolean onKeyPressed(int keyCode, int scanCode, int modifiers) {
        return nameField.onKeyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean onCharTyped(char chr, int modifiers) {
        return nameField.onCharTyped(chr, modifiers);
    }
}
