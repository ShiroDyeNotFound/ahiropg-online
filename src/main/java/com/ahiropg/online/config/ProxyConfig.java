package com.ahiropg.online.config;

import java.net.InetSocketAddress;

/**
 * Optional outbound proxy for Mojang API requests.
 *
 * <p>Some networks cannot reach {@code api.mojang.com} directly. When {@code enabled}
 * is false every request goes out directly, which is the default.</p>
 *
 * @param enabled  whether requests should be routed through the proxy
 * @param type     {@link Type#HTTP} or {@link Type#SOCKS5}
 * @param host     proxy host name or IP
 * @param port     proxy port, 1-65535
 * @param username SOCKS5 / HTTP proxy user, empty for no authentication
 * @param password matching password, empty for no authentication
 */
public record ProxyConfig(boolean enabled, Type type, String host, int port, String username, String password) {

    public enum Type {
        /** An HTTP proxy, also used for HTTPS targets via CONNECT. */
        HTTP,
        /** A SOCKS5 proxy, including username/password authentication. */
        SOCKS5
    }

    public static final ProxyConfig DISABLED = new ProxyConfig(false, Type.HTTP, "", 0, "", "");

    public boolean authenticated() {
        return !username.isEmpty();
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(host, port);
    }
}
