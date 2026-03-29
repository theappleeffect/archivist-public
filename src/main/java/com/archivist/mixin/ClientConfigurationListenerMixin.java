package com.archivist.mixin;

import com.archivist.bridge.MixinBridge;
import com.archivist.detection.DetectionPipeline;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConfigurationPacketListenerImpl.class)
public class ClientConfigurationListenerMixin {

    @Inject(method = "handleRegistryData", at = @At("HEAD"))
    private void archivist$onRegistryData(ClientboundRegistryDataPacket packet, CallbackInfo ci) {
        MixinBridge.State snap = MixinBridge.snapshot();
        DetectionPipeline p = snap.pipeline();
        if (p == null || !snap.active()) return;
        try {
            packet.entries().forEach(entry -> {
                try {
                    String ns = entry.id().getNamespace();
                    p.onRegistryNamespace(ns);
                } catch (Exception e) {
                    com.archivist.ArchivistMod.LOGGER.debug("Error processing registry entry", e);
                }
            });
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleRegistryData hook", e);
        }
    }
}
