package com.archivist.mixin;

import com.archivist.ArchivistMod;
import com.archivist.proxy.ProxyManager;
import com.archivist.proxy.ProxyProfile;
import com.archivist.account.SecureStorage;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;

/**
 * Injects SOCKS proxy handlers into the Netty pipeline during connection setup.
 */
@Mixin(Connection.class)
public class ConnectionProxyMixin {

    @Inject(method = "configureSerialization", at = @At("RETURN"))
    private static void archivist$injectProxy(ChannelPipeline pipeline, PacketFlow flow,
                                               boolean memoryOnly,
                                               net.minecraft.network.BandwidthDebugMonitor monitor,
                                               CallbackInfo ci) {
        try {
            ArchivistMod mod = ArchivistMod.getInstance();
            if (mod == null) return;

            ProxyManager proxyMgr = mod.getProxyManager();
            if (proxyMgr == null || !proxyMgr.isProxyEnabled()) return;

            var profileOpt = proxyMgr.getActiveProfile();
            if (profileOpt.isEmpty()) return;

            ProxyProfile profile = profileOpt.get();
            InetSocketAddress proxyAddr = new InetSocketAddress(profile.address, profile.port);

            String username = profile.username;
            String password = null;
            if (profile.passwordEncoded != null && !profile.passwordEncoded.isEmpty()) {
                password = SecureStorage.decrypt(profile.passwordEncoded);
            }

            switch (profile.type) {
                case SOCKS4 -> {
                    Socks4ProxyHandler handler = (username != null && !username.isEmpty())
                            ? new Socks4ProxyHandler(proxyAddr, username)
                            : new Socks4ProxyHandler(proxyAddr);
                    pipeline.addFirst("archivist_proxy", handler);
                    ArchivistMod.LOGGER.info("Injected SOCKS4 proxy: {}:{}", profile.address, profile.port);
                }
                case SOCKS5 -> {
                    Socks5ProxyHandler handler;
                    if (username != null && !username.isEmpty() && password != null) {
                        handler = new Socks5ProxyHandler(proxyAddr, username, password);
                    } else {
                        handler = new Socks5ProxyHandler(proxyAddr);
                    }
                    pipeline.addFirst("archivist_proxy", handler);
                    ArchivistMod.LOGGER.info("Injected SOCKS5 proxy: {}:{}", profile.address, profile.port);
                }
            }
        } catch (Exception e) {
            ArchivistMod.LOGGER.error("Failed to inject proxy handler", e);
        }
    }
}
