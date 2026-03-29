package com.archivist.proxy;

import java.util.UUID;

/**
 * A single proxy profile configuration.
 */
public final class ProxyProfile {

    public enum ProxyType { SOCKS4, SOCKS5 }

    public String id = UUID.randomUUID().toString();
    public String name = "";
    public ProxyType type = ProxyType.SOCKS5;
    public String address = "127.0.0.1";
    public int port = 1080;
    public String username = "";
    public String passwordEncoded = "";
    public boolean enabled = true;

    public ProxyProfile() {}

    public ProxyProfile(String name, ProxyType type, String address, int port) {
        this.name = name;
        this.type = type;
        this.address = address;
        this.port = port;
    }
}
