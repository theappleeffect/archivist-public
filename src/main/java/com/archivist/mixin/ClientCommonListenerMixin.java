package com.archivist.mixin;

import com.archivist.bridge.MixinBridge;
import com.archivist.data.LogEvent;
import com.archivist.data.ServerSession;
import com.archivist.detection.DetectionPipeline;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
public class ClientCommonListenerMixin {

    @Inject(method = "handleCustomPayload", at = @At("HEAD"))
    private void archivist$onCustomPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        MixinBridge.State snap = MixinBridge.snapshot();
        DetectionPipeline p = snap.pipeline();
        ServerSession s = snap.session();
        if (p == null || !snap.active()) return;
        try {
            if (packet.payload() instanceof BrandPayload brandPayload) {
                p.onBrandReceived(brandPayload.brand());
            } else {
                String namespace = packet.payload().type().id().getNamespace();
                p.onChannelNamespace(namespace);
                if (s != null) {
                    s.getEvents().post(
                            new LogEvent(LogEvent.Type.PACKET, "Custom packet: " + packet.payload().type().id()));
                }
            }
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleCustomPayload hook", e);
        }
    }

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"), cancellable = true)
    private void archivist$onResourcePack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        // Log the resource pack URL for detection
        MixinBridge.State snap = MixinBridge.snapshot();
        ServerSession s = snap.session();
        if (s != null && snap.active()) {
            try { s.setResourcePack(packet.url()); } catch (Exception e) {
                com.archivist.ArchivistMod.LOGGER.debug("Error recording resource pack URL", e);
            }
        }

        // Auto-handle: accept mandatory, decline optional — skip the prompt screen
        if (!com.archivist.ArchivistMod.getInstance().getConfig().autoHandleResourcePacks) return;
        try {
            ClientCommonPacketListenerImpl self = (ClientCommonPacketListenerImpl) (Object) this;
            if (packet.required()) {
                // Mandatory: fake a successful load so the server doesn't kick us
                self.send(new ServerboundResourcePackPacket(packet.id(),
                        ServerboundResourcePackPacket.Action.ACCEPTED));
                self.send(new ServerboundResourcePackPacket(packet.id(),
                        ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
                com.archivist.ArchivistMod.LOGGER.info("Auto-accepted mandatory resource pack: {}", packet.url());
            } else {
                // Optional: decline silently
                self.send(new ServerboundResourcePackPacket(packet.id(),
                        ServerboundResourcePackPacket.Action.DECLINED));
                com.archivist.ArchivistMod.LOGGER.info("Auto-declined optional resource pack: {}", packet.url());
            }
            ci.cancel();
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.warn("Failed to auto-handle resource pack, falling back to default", e);
            // Don't cancel — let vanilla handle it if our approach fails
        }
    }
}
