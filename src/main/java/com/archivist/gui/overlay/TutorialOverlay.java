package com.archivist.gui.overlay;

import com.archivist.config.ArchivistConfig;
import com.archivist.config.ConfigManager;
import com.archivist.gui.panel.PanelModeLayout;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import com.archivist.gui.widgets.DraggableWindow;
import com.archivist.gui.widgets.Taskbar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
//? if >=1.21.9
import net.minecraft.client.renderer.RenderPipelines;
//? if >=1.21.11
import net.minecraft.resources.Identifier;
//? if <1.21.11
/*import net.minecraft.resources.ResourceLocation;*/

import java.util.List;
import java.util.function.Supplier;

/**
 * Step-by-step tutorial overlay shown on first GUI open.
 * Renders a dark scrim with a cutout over the highlighted region,
 * plus a hint box with navigation buttons.
 */
public class TutorialOverlay {

    private static final int HINT_WIDTH = 240;
    private static final int HINT_WIDTH_IMAGE = 200; // Narrower for image steps to keep image smaller
    private static final int HINT_PADDING = 8;
    private static final int BUTTON_W = 50;
    private static final int BUTTON_H = 12;
    private static final int DOT_RADIUS = 2;

    //? if >=1.21.11 {
    private static final Identifier GUI_RULE_EXAMPLE =
            Identifier.fromNamespaceAndPath("archivist", "textures/gui/gui_rule_example.png");
    //?} else {
    /*private static final ResourceLocation GUI_RULE_EXAMPLE =
            ResourceLocation.fromNamespaceAndPath("archivist", "textures/gui/gui_rule_example.png");*/
    //?}
    private static final int GUI_RULE_IMG_W = 447;
    private static final int GUI_RULE_IMG_H = 403;

    private boolean active = true;
    private int currentStep = 0;
    private long tickCount = 0;

    private final ArchivistConfig config;

    // Suppliers for layout bounds — set by ArchivistScreen each init()
    private Supplier<PanelModeLayout> panelLayoutSupplier;
    private Supplier<Boolean> panelModeSupplier;
    private Supplier<List<DraggableWindow>> windowsSupplier;
    private Supplier<Taskbar> taskbarSupplier;
    private Runnable forceSettingsGeneralTab;
    private Supplier<int[]> layoutSectionBoundsSupplier;
    private Runnable onFinishCallback;

    public TutorialOverlay(ArchivistConfig config) {
        this.config = config;
    }

    public void setOnFinishCallback(Runnable callback) {
        this.onFinishCallback = callback;
    }

    public void setForceSettingsGeneralTab(Runnable callback) {
        this.forceSettingsGeneralTab = callback;
    }

    public void setLayoutSectionBoundsSupplier(Supplier<int[]> supplier) {
        this.layoutSectionBoundsSupplier = supplier;
    }

    public void setLayoutSuppliers(Supplier<PanelModeLayout> panelLayout,
                                   Supplier<Boolean> panelMode,
                                   Supplier<List<DraggableWindow>> windows,
                                   Supplier<Taskbar> taskbar) {
        this.panelLayoutSupplier = panelLayout;
        this.panelModeSupplier = panelMode;
        this.windowsSupplier = windows;
        this.taskbarSupplier = taskbar;
    }

    public boolean isActive() { return active; }

    public void tick() { tickCount++; }

    // ── Step Definitions ──

    private record Step(String text, String target, boolean hasImage) {
        Step(String text, String target) {
            this(text, target, false);
        }
    }

    private static final Step[] STEPS = {
        new Step("Welcome to Archivist! This quick tour will\nshow you around.\nPress Next or Escape to skip.", null),
        new Step("Use the sidebar to switch between sections.\nYou can drag sections out into\nfloating windows.", "sidebar"),
        new Step("Server & Plugins shows connection details,\nplugins, and world info.\nClick section headers to collapse them.", "server"),
        new Step("Press X on any server inventory to\ncapture a GUI Rule. Select slots and\nsave a rule to auto-detect the plugin\nwhen that screen is seen again.", null, true),
        new Step("The console shows live events.\nType 'help' for available commands.", "console"),
        new Step("Customize themes, layout, keybinds,\nand export options here.", "settings"),
        new Step("In Settings > General, use\nLayout Mode to switch between\nDynamic (sidebar) and Windows\n(floating panels), and\nSidebar Position to move it\nleft or right.", "settings_layout"),
        new Step("You're all set! Press Z anytime to\nopen Archivist.\n(Ctrl+F to search)", null),
    };

    // ── Rendering ──

    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (!active) return;
        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        ColorScheme cs = ColorScheme.get();
        Step step = STEPS[currentStep];

        // Resolve highlight region
        int[] region = resolveRegion(step.target, screenW, screenH);

        // Draw scrim with cutout
        if (region != null) {
            int rx = region[0], ry = region[1], rw = region[2], rh = region[3];
            // Top
            g.fill(0, 0, screenW, ry, cs.screenOverlay());
            // Bottom
            g.fill(0, ry + rh, screenW, screenH, cs.screenOverlay());
            // Left
            g.fill(0, ry, rx, ry + rh, cs.screenOverlay());
            // Right
            g.fill(rx + rw, ry, screenW, ry + rh, cs.screenOverlay());

            // Pulsing border around cutout
            float pulse = (float)(0.6 + 0.4 * Math.sin(tickCount * 0.15));
            int borderAlpha = (int)(pulse * 255);
            int borderColor = ColorScheme.withAlpha(cs.accent(), borderAlpha);
            RenderUtils.drawBorder(g, rx - 1, ry - 1, rw + 2, rh + 2, borderColor);
            RenderUtils.drawBorder(g, rx - 2, ry - 2, rw + 4, rh + 4, borderColor);
        } else {
            g.fill(0, 0, screenW, screenH, cs.screenOverlay());
        }

        // Use a wider hint box for image steps
        int stepHintW = step.hasImage ? HINT_WIDTH_IMAGE : HINT_WIDTH;

        // Calculate image dimensions for this step
        int imgDisplayW = 0, imgDisplayH = 0;
        if (step.hasImage) {
            // Scale image to fit within the wider hint box
            int maxImgW = stepHintW - HINT_PADDING * 2;
            float scale = (float) maxImgW / GUI_RULE_IMG_W;
            imgDisplayW = maxImgW;
            imgDisplayH = (int)(GUI_RULE_IMG_H * scale);
        }

        // Calculate hint box position
        String[] lines = step.text.split("\n");
        int textH = lines.length * 10;
        int imageSection = step.hasImage ? imgDisplayH + 6 : 0;
        int hintH = HINT_PADDING * 2 + textH + 6 + imageSection + BUTTON_H + 6 + DOT_RADIUS * 2 + 4;

        // Shrink image if hint box would exceed screen height
        int maxHintH = screenH - 8;
        if (step.hasImage && hintH > maxHintH) {
            int excess = hintH - maxHintH;
            imgDisplayH -= excess;
            if (imgDisplayH < 20) imgDisplayH = 20;
            imgDisplayW = (int)((float) imgDisplayH / GUI_RULE_IMG_H * GUI_RULE_IMG_W);
            imageSection = imgDisplayH + 6;
            hintH = HINT_PADDING * 2 + textH + 6 + imageSection + BUTTON_H + 6 + DOT_RADIUS * 2 + 4;
        }

        int hintX, hintY;

        if (region != null) {
            // Position hint box to the right of the region if there's space, else below
            hintX = region[0] + region[2] + 8;
            hintY = region[1];
            if (hintX + stepHintW > screenW - 4) {
                hintX = region[0] - stepHintW - 8;
            }
            if (hintX < 4) {
                hintX = region[0] + (region[2] - stepHintW) / 2;
                hintY = region[1] + region[3] + 8;
            }
            if (hintY + hintH > screenH - 4) {
                hintY = screenH - hintH - 4;
            }
            if (hintY < 4) hintY = 4;
        } else {
            hintX = (screenW - stepHintW) / 2;
            hintY = (screenH - hintH) / 2;
        }

        // Draw hint box
        RenderUtils.drawRect(g, hintX, hintY, stepHintW, hintH, cs.tooltipBg());
        RenderUtils.drawBorder(g, hintX, hintY, stepHintW, hintH, cs.accent());

        // Draw text
        int ty = hintY + HINT_PADDING;
        for (String line : lines) {
            RenderUtils.drawText(g, line, hintX + HINT_PADDING, ty, cs.textPrimary());
            ty += 10;
        }
        ty += 6;

        // Draw image if this step has one
        if (step.hasImage) {
            int imgX = hintX + (stepHintW - imgDisplayW) / 2;
            //? if >=1.21.9
            g.blit(RenderPipelines.GUI_TEXTURED, GUI_RULE_EXAMPLE, imgX, ty, 0.0f, 0.0f, imgDisplayW, imgDisplayH, GUI_RULE_IMG_W, GUI_RULE_IMG_H);
            //? if >=1.21.2 && <1.21.9
            /*g.blit(net.minecraft.client.renderer.RenderType::guiTextured, GUI_RULE_EXAMPLE, imgX, ty, 0.0f, 0.0f, imgDisplayW, imgDisplayH, GUI_RULE_IMG_W, GUI_RULE_IMG_H);*/
            //? if <1.21.2
            /*g.blit(GUI_RULE_EXAMPLE, imgX, ty, 0, 0, imgDisplayW, imgDisplayH, GUI_RULE_IMG_W, GUI_RULE_IMG_H);*/

            // Border around image
            RenderUtils.drawBorder(g, imgX - 1, ty - 1, imgDisplayW + 2, imgDisplayH + 2, cs.windowBorder());
            ty += imgDisplayH + 6;
        }

        // Draw buttons
        boolean isLast = currentStep == STEPS.length - 1;
        boolean isFirst = currentStep == 0;

        // Skip button (left side) — not shown on last step
        int skipX = hintX + HINT_PADDING;
        int skipY = ty;
        if (!isLast) {
            drawButton(g, "Skip", skipX, skipY, BUTTON_W, BUTTON_H,
                    mouseX, mouseY, cs.textSecondary(), cs.button(), cs.buttonHover());
        }

        // Next/Finish button (right side)
        String nextLabel = isLast ? "Finish" : (isFirst ? "Start" : "Next");
        int nextX = hintX + stepHintW - HINT_PADDING - BUTTON_W;
        int nextY = ty;
        drawButton(g, nextLabel, nextX, nextY, BUTTON_W, BUTTON_H,
                mouseX, mouseY, cs.textPrimary(), cs.accentDim(), cs.accent());

        ty += BUTTON_H + 6;

        // Step indicator dots
        int totalDots = STEPS.length;
        int dotSpacing = DOT_RADIUS * 2 + 4;
        int dotsWidth = totalDots * dotSpacing - 4;
        int dotStartX = hintX + (stepHintW - dotsWidth) / 2;
        for (int i = 0; i < totalDots; i++) {
            int dx = dotStartX + i * dotSpacing + DOT_RADIUS;
            int dy = ty + DOT_RADIUS;
            int dotColor = (i == currentStep) ? cs.accent() : cs.textSecondary();
            // Draw a small filled circle approximation (2x2 or 3x3 rect)
            g.fill(dx - DOT_RADIUS, dy - DOT_RADIUS, dx + DOT_RADIUS, dy + DOT_RADIUS, dotColor);
        }

        // Cache button positions for click handling
        this.cachedSkipX = skipX;
        this.cachedSkipY = skipY;
        this.cachedNextX = nextX;
        this.cachedNextY = nextY;
        this.cachedHintX = hintX;
        this.cachedHintY = hintY;
        this.cachedHintH = hintH;
        this.cachedIsLast = isLast;
    }

    // Cached button positions from last render
    private int cachedSkipX, cachedSkipY, cachedNextX, cachedNextY;
    private int cachedHintX, cachedHintY, cachedHintH;
    private boolean cachedIsLast;

    private void drawButton(GuiGraphics g, String label, int x, int y, int w, int h,
                            int mouseX, int mouseY, int textColor, int bgColor, int hoverColor) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        RenderUtils.drawRect(g, x, y, w, h, hovered ? hoverColor : bgColor);
        RenderUtils.drawBorder(g, x, y, w, h, hovered ? ColorScheme.get().accent() : ColorScheme.get().windowBorder());
        int textW = Minecraft.getInstance().font.width(label);
        RenderUtils.drawText(g, label, x + (w - textW) / 2, y + (h - 8) / 2, textColor);
    }

    // ── Input ──

    public boolean onMouseClicked(double mouseX, double mouseY, int button) {
        if (!active) return false;

        // Check Next/Finish button
        if (mouseX >= cachedNextX && mouseX < cachedNextX + BUTTON_W
                && mouseY >= cachedNextY && mouseY < cachedNextY + BUTTON_H) {
            if (currentStep < STEPS.length - 1) {
                currentStep++;
            } else {
                finish();
            }
            return true;
        }

        // Check Skip button
        if (!cachedIsLast && mouseX >= cachedSkipX && mouseX < cachedSkipX + BUTTON_W
                && mouseY >= cachedSkipY && mouseY < cachedSkipY + BUTTON_H) {
            finish();
            return true;
        }

        // Consume all other clicks (don't let them through)
        return true;
    }

    public boolean onKeyPressed(int keyCode) {
        if (!active) return false;
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            finish();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            if (currentStep < STEPS.length - 1) {
                currentStep++;
            } else {
                finish();
            }
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT) {
            if (currentStep > 0) currentStep--;
            return true;
        }
        return true; // consume all keys
    }

    private void finish() {
        active = false;
        config.tutorial = false;
        ConfigManager.save(config);
        if (onFinishCallback != null) onFinishCallback.run();
    }

    // ── Region Resolution ──

    private int[] resolveRegion(String target, int screenW, int screenH) {
        if (target == null) return null;

        boolean panelMode = panelModeSupplier != null && Boolean.TRUE.equals(panelModeSupplier.get());

        if ("sidebar".equals(target)) {
            if (panelMode && panelLayoutSupplier != null) {
                PanelModeLayout pl = panelLayoutSupplier.get();
                if (pl != null) {
                    int sw = pl.getSidebarWidth();
                    String side = pl.getSidebarSide();
                    int sx = "right".equals(side) ? pl.getX() + pl.getWidth() - sw : pl.getX();
                    return new int[]{sx, pl.getY(), sw, pl.getHeight()};
                }
            }
            // Window mode — highlight the taskbar
            if (taskbarSupplier != null) {
                Taskbar tb = taskbarSupplier.get();
                if (tb != null) {
                    return new int[]{tb.getX(), tb.getY(), tb.getWidth(), tb.getHeight()};
                }
            }
            return null;
        }

        // Force General tab when highlighting Layout Mode / Sidebar Position
        if ("settings_layout".equals(target) && forceSettingsGeneralTab != null) {
            forceSettingsGeneralTab.run();
        }

        // Map target name to window ID
        String resolvedTarget = "settings_layout".equals(target) ? "settings" : target;
        String windowId = switch (resolvedTarget) {
            case "server" -> panelMode ? null : "server_info";
            case "console" -> panelMode ? null : "console";
            case "settings" -> panelMode ? null : "settings";
            default -> null;
        };

        if (panelMode && panelLayoutSupplier != null) {
            // In panel mode, highlight the content area
            PanelModeLayout pl = panelLayoutSupplier.get();
            if (pl != null) {
                // Switch to the relevant section
                String section = switch (resolvedTarget) {
                    case "server" -> "Server & Plugins";
                    case "console" -> "Console";
                    case "settings" -> "Settings";
                    default -> null;
                };
                if (section != null) {
                    pl.setActiveSection(section);
                }
                var cp = pl.getContentPanel();
                if (cp != null) {
                    if ("settings_layout".equals(target)) {
                        if (layoutSectionBoundsSupplier != null) {
                            int[] bounds = layoutSectionBoundsSupplier.get();
                            if (bounds != null) return bounds;
                        }
                    }
                    return new int[]{cp.getX(), cp.getY(), cp.getWidth(), cp.getHeight()};
                }
            }
            return null;
        }

        // Window mode — find the window by ID and show it
        if (windowId != null && windowsSupplier != null) {
            for (DraggableWindow w : windowsSupplier.get()) {
                if (windowId.equals(w.getId())) {
                    if (!w.isVisible()) {
                        w.setVisible(true);
                    }
                    if ("settings_layout".equals(target)) {
                        if (layoutSectionBoundsSupplier != null) {
                            int[] bounds = layoutSectionBoundsSupplier.get();
                            if (bounds != null) return bounds;
                        }
                    }
                    return new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()};
                }
            }
        }

        return null;
    }
}
