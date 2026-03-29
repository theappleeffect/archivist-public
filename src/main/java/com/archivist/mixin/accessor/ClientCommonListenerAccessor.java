package com.archivist.mixin.accessor;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonListenerAccessor {

    @Accessor("serverBrand")
    String getServerBrand();
}
