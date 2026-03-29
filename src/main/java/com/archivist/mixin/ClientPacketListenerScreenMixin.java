package com.archivist.mixin;

import com.archivist.bridge.MixinBridge;
import com.archivist.detection.fingerprint.CapturedItem;
import net.minecraft.client.multiplayer.ClientPacketListener;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerScreenMixin {

    @Unique private int archivist$pendingSyncId = -1;
    @Unique private String archivist$pendingTitle = "";
    @Unique private String archivist$pendingContainerType = "";

    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void archivist$onOpenScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        MixinBridge.State bridgeState = MixinBridge.snapshot();
        if (!bridgeState.active()) return;
        try {
            archivist$pendingSyncId = packet.getContainerId();
            archivist$pendingTitle = packet.getTitle().getString();
            archivist$pendingContainerType = packet.getType().toString();
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleOpenScreen hook", e);
        }
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void archivist$onContainerContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        MixinBridge.State bridgeState2 = MixinBridge.snapshot();
        if (!bridgeState2.active()) return;
        try {
            if (packet./*? if >=1.21.5 {*/containerId()/*?} else {*//*getContainerId()*//*?}*/ != archivist$pendingSyncId || archivist$pendingSyncId == -1) return;

            List<ItemStack> stacks = packet./*? if >=1.21.5 {*/items()/*?} else {*//*getItems()*//*?}*/;
            List<CapturedItem> items = new ArrayList<>();

            for (int i = 0; i < stacks.size(); i++) {
                ItemStack stack = stacks.get(i);
                if (stack.isEmpty()) continue;

                String materialId;
                try {
                    materialId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                } catch (Exception e2) {
                    materialId = "unknown";
                }

                Component nameComponent = stack.getHoverName();
                String displayName = nameComponent.getString();
                String displayNameJson = archivist$componentToJson(nameComponent);

                List<String> lore = new ArrayList<>();
                List<String> loreJson = new ArrayList<>();
                ItemLore itemLore = stack.get(DataComponents.LORE);
                if (itemLore != null) {
                    for (var line : itemLore.lines()) {
                        lore.add(line.getString());
                        loreJson.add(archivist$componentToJson(line));
                    }
                }

                int customModelData = 0;
                try {
                    var cmd = stack.get(DataComponents.CUSTOM_MODEL_DATA);
                    //? if >=1.21.4 {
                    if (cmd != null) {
                        var floats = cmd.floats();
                        if (floats != null && !floats.isEmpty()) {
                            customModelData = (int) floats.getFirst().floatValue();
                        }
                    }
                    //?} else {
                    /*if (cmd != null) {
                        customModelData = cmd.value();
                    }*/
                    //?}
                } catch (Exception ignored) {}

                items.add(new CapturedItem(
                        i, materialId, displayName, displayNameJson,
                        lore, loreJson, stack.getCount(), customModelData, stack.hasFoil()
                ));
            }

            // Passive GUI detection (runs on every GUI open)
            var passiveCallback = bridgeState2.passiveGuiCallback();
            if (passiveCallback != null) {
                var capture = com.archivist.detection.fingerprint.GuiCapture.capture(
                        archivist$pendingSyncId, archivist$pendingContainerType,
                        archivist$pendingTitle, "", items);
                passiveCallback.accept(capture);
            }

            archivist$pendingSyncId = -1;
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleContainerContent hook", e);
        }
    }

    @Unique
    private static String archivist$componentToJson(Component component) {
        try {
            var mc = Minecraft.getInstance();
            if (mc.level != null) {
                RegistryAccess registryAccess = mc.level.registryAccess();
                var ops = RegistryOps.create(JsonOps.INSTANCE, registryAccess);
                var result = ComponentSerialization.CODEC.encodeStart(ops, component);
                if (result.isSuccess()) {
                    return result.getOrThrow().toString();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}
