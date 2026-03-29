package com.archivist.mixin;

import com.archivist.ArchivistMod;
import com.archivist.data.LogEvent;
import com.archivist.detection.fingerprint.RuleCaptureManager;
import com.archivist.detection.fingerprint.model.GuiRule;
import com.archivist.gui.render.ColorScheme;
import com.archivist.gui.render.RenderUtils;
import com.archivist.gui.widgets.TextField;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if >=1.21.9 {
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
//?}
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to render the capture overlay bar (styled like a DraggableWindow) with
 * open animation, slot highlights, and keyboard/mouse input during capture mode.
 */
@Mixin(AbstractContainerScreen.class)
public class ContainerScreenCaptureMixin {

    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;

    // Button hit-test areas (recalculated each frame)
    @Unique private int archivist$saveBtnX, archivist$saveBtnY, archivist$saveBtnW, archivist$saveBtnH;
    @Unique private int archivist$cancelBtnX, archivist$cancelBtnY, archivist$cancelBtnW, archivist$cancelBtnH;

    // Animation state
    @Unique private float archivist$animProgress = 1f;
    @Unique private boolean archivist$animActive = false;
    @Unique private boolean archivist$wasCapturing = false;

    private static final int TITLE_H = 16;
    private static final int BAR_HEIGHT = 54; // title(16) + pad(4) + textfield(14) + gap(2) + buttons(14) + pad(4)
    private static final int BAR_GAP = 4;
    private static final int BAR_PAD = 4;
    private static final int BTN_W = 40;
    private static final int BTN_H = 14;
    private static final float ANIM_SPEED = 0.1f;

    @Unique
    private static float archivist$easeOut(float t) {
        return 1f - (1f - t) * (1f - t);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void archivist$renderCaptureOverlay(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod == null) return;
        RuleCaptureManager mgr = mod.getRuleCaptureManager();
        boolean capturing = mgr != null && mgr.getState() == RuleCaptureManager.State.SELECTING;

        // Detect transition to start animation
        if (capturing && !archivist$wasCapturing) {
            archivist$animProgress = 0f;
            archivist$animActive = true;
        }
        archivist$wasCapturing = capturing;

        if (!capturing) return;

        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        ColorScheme cs = ColorScheme.get();
        int accent = cs.accent();
        int overlayColor = (0x60 << 24) | (accent & 0x00FFFFFF);

        // ── Slot highlights (not animated — always instant) ──────────────────
        for (Slot slot : screen.getMenu().slots) {
            if (mgr.isSlotSelected(slot.index)) {
                int x = slot.x + leftPos;
                int y = slot.y + topPos;
                g.fill(x, y, x + 16, y + 16, overlayColor);
            }
        }

        // ── Animation update ─────────────────────────────────────────────────
        if (archivist$animActive) {
            archivist$animProgress += ANIM_SPEED;
            if (archivist$animProgress >= 1f) {
                archivist$animProgress = 1f;
                archivist$animActive = false;
            }
        }

        // ── Bar geometry ─────────────────────────────────────────────────────
        int barW = Math.max(imageWidth, 200);
        int barX = leftPos + (imageWidth - barW) / 2;
        int barY = topPos - BAR_HEIGHT - BAR_GAP;
        if (barY < 2) barY = 2;

        // ── Animation transform (scale from center, matching DraggableWindow) ─
        float scale = archivist$animActive ? archivist$easeOut(archivist$animProgress) : 1f;
        if (scale < 0.01f) scale = 0.01f;
        boolean animating = archivist$animActive;

        if (animating) {
            float centerX = barX + barW / 2f;
            float centerY = barY + BAR_HEIGHT / 2f;
            var pose = g.pose();
            //? if >=1.21.9 {
            pose.pushMatrix();
            pose.translate(centerX, centerY);
            pose.scale(scale, scale);
            pose.translate(-centerX, -centerY);
            //?} else {
            /*pose.pushPose();
            pose.translate(centerX, centerY, 0);
            pose.scale(scale, scale, 1);
            pose.translate(-centerX, -centerY, 0);*/
            //?}
        }

        // ── Title bar (matches DraggableWindow title bar) ────────────────────
        RenderUtils.drawRect(g, barX, barY, barW, TITLE_H, cs.titleBar());
        RenderUtils.drawText(g, "Capture Rule",
                barX + 4, barY + (TITLE_H - RenderUtils.scaledFontHeight()) / 2, cs.titleText());

        // ── Content area ─────────────────────────────────────────────────────
        int contentY = barY + TITLE_H;
        int contentH = BAR_HEIGHT - TITLE_H;
        RenderUtils.drawRect(g, barX, contentY, barW, contentH, cs.windowBackground());

        // ── Border around entire bar ─────────────────────────────────────────
        RenderUtils.drawBorder(g, barX, barY, barW, BAR_HEIGHT, cs.windowBorderActive());

        // ── Text field ───────────────────────────────────────────────────────
        int tfX = barX + BAR_PAD;
        int tfY = contentY + BAR_PAD;
        int tfW = barW - BAR_PAD * 2;
        int tfH = 14;

        TextField nameField = mgr.getNameField();
        if (nameField == null) {
            nameField = new TextField(tfX, tfY, tfW, "plugin name...");
            nameField.setFocused(true);
            nameField.setOnChange(text -> {
                ArchivistMod m = ArchivistMod.getInstance();
                if (m != null) m.getRuleCaptureManager().setInputText(text.toLowerCase());
            });
            mgr.setNameField(nameField);
            String existing = mgr.getInputText();
            if (existing != null && !existing.isEmpty()) {
                nameField.setText(existing);
            }
        } else {
            nameField.setPosition(tfX, tfY);
            nameField.setSize(tfW, tfH);
        }

        nameField.render(g, mouseX, mouseY, delta);

        // ── Row 2: slot count + Save + Cancel ────────────────────────────────
        int row2Y = tfY + tfH + 2;
        int slotCount = mgr.getSelectedSlots().size();
        String slotsText = slotCount + " slot" + (slotCount != 1 ? "s" : "") + " selected";
        RenderUtils.drawText(g, slotsText, barX + BAR_PAD, row2Y + 2, cs.textSecondary());

        // Save button
        archivist$saveBtnW = BTN_W;
        archivist$saveBtnH = BTN_H;
        archivist$saveBtnX = barX + barW - BAR_PAD - BTN_W;
        archivist$saveBtnY = row2Y;
        boolean saveHovered = mouseX >= archivist$saveBtnX && mouseX < archivist$saveBtnX + archivist$saveBtnW
                && mouseY >= archivist$saveBtnY && mouseY < archivist$saveBtnY + archivist$saveBtnH;
        boolean canSave = slotCount > 0 && !mgr.getInputText().isBlank();
        int saveBg = !canSave ? ColorScheme.withAlpha(cs.button(), 100)
                : saveHovered ? cs.buttonHover() : cs.button();
        RenderUtils.drawRect(g, archivist$saveBtnX, archivist$saveBtnY, BTN_W, BTN_H, saveBg);
        int saveTxtW = RenderUtils.scaledTextWidth("Save");
        RenderUtils.drawText(g, "Save",
                archivist$saveBtnX + (BTN_W - saveTxtW) / 2,
                archivist$saveBtnY + (BTN_H - RenderUtils.scaledFontHeight()) / 2,
                canSave ? cs.buttonText() : ColorScheme.withAlpha(cs.buttonText(), 100));

        // Cancel button
        archivist$cancelBtnW = BTN_W;
        archivist$cancelBtnH = BTN_H;
        archivist$cancelBtnX = archivist$saveBtnX - BTN_W - 4;
        archivist$cancelBtnY = row2Y;
        boolean cancelHovered = mouseX >= archivist$cancelBtnX && mouseX < archivist$cancelBtnX + archivist$cancelBtnW
                && mouseY >= archivist$cancelBtnY && mouseY < archivist$cancelBtnY + archivist$cancelBtnH;
        int cancelBg = cancelHovered ? cs.buttonHover() : cs.button();
        RenderUtils.drawRect(g, archivist$cancelBtnX, archivist$cancelBtnY, BTN_W, BTN_H, cancelBg);
        int cancelTxtW = RenderUtils.scaledTextWidth("Cancel");
        RenderUtils.drawText(g, "Cancel",
                archivist$cancelBtnX + (BTN_W - cancelTxtW) / 2,
                archivist$cancelBtnY + (BTN_H - RenderUtils.scaledFontHeight()) / 2,
                cs.buttonText());

        // ── Pop animation transform ──────────────────────────────────────────
        if (animating) {
            //? if >=1.21.9
            g.pose().popMatrix();
            //? if <1.21.9
            /*g.pose().popPose();*/
        }
    }

    //? if >=1.21.9 {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void archivist$onMouseClicked(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {
        archivist$handleMouseClicked((int) event.x(), (int) event.y(), event.button(), cir);
    }
    //?} else {
    /*@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void archivist$onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        archivist$handleMouseClicked((int) mouseX, (int) mouseY, button, cir);
    }*/
    //?}

    @Unique
    private void archivist$handleMouseClicked(int mx, int my, int button, CallbackInfoReturnable<Boolean> cir) {
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod == null) return;
        RuleCaptureManager mgr = mod.getRuleCaptureManager();
        if (mgr == null || mgr.getState() != RuleCaptureManager.State.SELECTING) return;
        if (archivist$animActive) { cir.setReturnValue(true); return; }

        if (button != 0) return;

        // Check Save button
        if (mx >= archivist$saveBtnX && mx < archivist$saveBtnX + archivist$saveBtnW
                && my >= archivist$saveBtnY && my < archivist$saveBtnY + archivist$saveBtnH) {
            archivist$trySave();
            cir.setReturnValue(true);
            return;
        }

        // Check Cancel button
        if (mx >= archivist$cancelBtnX && mx < archivist$cancelBtnX + archivist$cancelBtnW
                && my >= archivist$cancelBtnY && my < archivist$cancelBtnY + archivist$cancelBtnH) {
            mgr.cancel();
            mod.getEventBus().post(new LogEvent(LogEvent.Type.SYSTEM, "Capture cancelled"));
            cir.setReturnValue(true);
            return;
        }

        // Check text field click
        TextField nameField = mgr.getNameField();
        if (nameField != null && nameField.onMouseClicked(mx, my, 0)) {
            cir.setReturnValue(true);
            return;
        }

        // Check slot toggle
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
        for (Slot slot : screen.getMenu().slots) {
            int sx = slot.x + leftPos;
            int sy = slot.y + topPos;
            if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) {
                if (slot.hasItem()) {
                    mgr.toggleSlot(slot.index);
                    cir.setReturnValue(true);
                    return;
                }
            }
        }
    }

    //? if >=1.21.9 {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void archivist$onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        archivist$handleKeyPressed(event.key(), event.scancode(), event.modifiers(), cir);
    }
    //?} else {
    /*@Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void archivist$onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        archivist$handleKeyPressed(keyCode, scanCode, modifiers, cir);
    }*/
    //?}

    @Unique
    private void archivist$handleKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod == null) return;
        RuleCaptureManager mgr = mod.getRuleCaptureManager();
        if (mgr == null || mgr.getState() != RuleCaptureManager.State.SELECTING) return;

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            archivist$trySave();
            cir.setReturnValue(true);
            return;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            mgr.cancel();
            mod.getEventBus().post(new LogEvent(LogEvent.Type.SYSTEM, "Capture cancelled"));
            cir.setReturnValue(true);
            return;
        }

        // Forward editing keys to the text field
        TextField nameField = mgr.getNameField();
        if (nameField != null) {
            nameField.onKeyPressed(keyCode, scanCode, modifiers);

            // charTyped is not available on AbstractContainerScreen (interface default),
            // so generate characters from key codes for printable input
            boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
            if (!ctrl) {
                char c = archivist$keyToChar(keyCode, modifiers);
                if (c != 0) {
                    nameField.onCharTyped(c, modifiers);
                }
            }

            mgr.setInputText(nameField.getText().toLowerCase());
        }
        cir.setReturnValue(true);
    }

    /** Map GLFW key codes to lowercase characters for plugin name input. */
    @Unique
    private static char archivist$keyToChar(int keyCode, int modifiers) {
        if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
            return (char) ('a' + (keyCode - GLFW.GLFW_KEY_A));
        }
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
            if (!shift) return (char) ('0' + (keyCode - GLFW.GLFW_KEY_0));
            return 0;
        }
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (keyCode == GLFW.GLFW_KEY_SPACE) return ' ';
        if (keyCode == GLFW.GLFW_KEY_MINUS && shift) return '_';
        if (keyCode == GLFW.GLFW_KEY_MINUS) return '-';
        if (keyCode == GLFW.GLFW_KEY_PERIOD) return '.';
        return 0;
    }

    @Unique
    private void archivist$trySave() {
        ArchivistMod mod = ArchivistMod.getInstance();
        if (mod == null) return;
        RuleCaptureManager mgr = mod.getRuleCaptureManager();
        if (mgr == null) return;

        if (mgr.getSelectedSlots().isEmpty() || mgr.getInputText().isBlank()) return;

        GuiRule rule = mgr.buildRule(mod.getGuiRuleDatabase());
        if (rule != null) {
            mod.getEventBus().post(new LogEvent(LogEvent.Type.SYSTEM,
                    "Saved rule: " + rule.pluginName() + " (" + rule.patterns().size() + " patterns)"));
        } else {
            mod.getEventBus().post(new LogEvent(LogEvent.Type.ERROR, "Failed to save rule"));
        }
    }
}
