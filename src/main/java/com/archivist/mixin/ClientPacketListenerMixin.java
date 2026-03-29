package com.archivist.mixin;

import com.archivist.bridge.MixinBridge;
import com.archivist.data.LogEvent;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Shadow
    private CommandDispatcher<SharedSuggestionProvider> commands;

    @Inject(method = "handleCommands", at = @At("TAIL"))
    private void archivist$onCommandTree(ClientboundCommandsPacket packet, CallbackInfo ci) {
        var snap = MixinBridge.snapshot();
        var p = snap.pipeline();
        if (p == null || !snap.active()) return;
        try {
            p.onCommandTree(this.commands);
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleCommands hook", e);
        }
    }

    @Inject(method = "handleLogin", at = @At("TAIL"))
    private void archivist$onLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        var snap = MixinBridge.snapshot();
        var p = snap.pipeline();
        var s = snap.session();
        if (s == null || !snap.active()) return;
        try {
            String dim = packet.commonPlayerSpawnInfo().dimension()./*? if >=1.21.11 {*/identifier()/*?} else {*//*location()*//*?}*/.toString();
            s.setDimension(dim);
            s.addWorld(dim, null);

            String gamemode = packet.commonPlayerSpawnInfo().gameType().name();
            s.getEvents().post(new LogEvent(LogEvent.Type.GAMEMODE, "Gamemode: " + gamemode));

            // Feed gamemode into session confidence for lobby detection
            if (p != null) p.onGamemodeReceived(gamemode);
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleLogin hook", e);
        }
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void archivist$onRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        var snap = MixinBridge.snapshot();
        var s = snap.session();
        if (s == null || !snap.active()) return;
        try {
            String dim = packet.commonPlayerSpawnInfo().dimension()./*? if >=1.21.11 {*/identifier()/*?} else {*//*location()*//*?}*/.toString();
            s.setDimension(dim);
            s.addWorld(dim, null);
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleRespawn hook", e);
        }
    }

    @Inject(method = "handleSystemChat", at = @At("HEAD"))
    private void archivist$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        var snap = MixinBridge.snapshot();
        var p = snap.pipeline();
        if (p == null || !snap.active()) return;
        try {
            Component content = packet.content();
            if (content != null) {
                p.onChatMessage(content.getString());
            }
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleSystemChat hook", e);
        }
    }

    @Inject(method = "handlePlayerChat", at = @At("HEAD"))
    private void archivist$onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
        var snap = MixinBridge.snapshot();
        var p = snap.pipeline();
        if (p == null || !snap.active()) return;
        try {
            p.onChatMessage(Component.nullToEmpty(packet.body().content()).getString());
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handlePlayerChat hook", e);
        }
    }

    @Inject(method = "handleTabListCustomisation", at = @At("HEAD"))
    private void archivist$onTabList(ClientboundTabListPacket packet, CallbackInfo ci) {
        var snap = MixinBridge.snapshot();
        var p = snap.pipeline();
        if (p == null || !snap.active()) return;
        try {
            p.onTabList(packet.header().getString(), packet.footer().getString());
        } catch (Exception e) {
            com.archivist.ArchivistMod.LOGGER.debug("Error in handleTabList hook", e);
        }
    }
}
