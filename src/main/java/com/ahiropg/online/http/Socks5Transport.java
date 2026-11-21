package com.ahiropg.online.http;

import com.ahiropg.online.config.ProxyConfig;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Tunnels one HTTPS GET through a SOCKS5 proxy.
 *
 * <p>The JDK gives us no usable option here, so this is a small, explicit client:</p>
 * <ul>
 *   <li>{@link java.net.http.HttpClient} <b>silently ignores</b> a SOCKS proxy in the
 *       proxy selector and connects directly (JDK-8214513), which would look like the
 *       proxy is working while traffic actually bypasses it.</li>
 *   <li>{@link java.net.HttpURLConnection} does support SOCKS, but it resolves the host
 *       locally and hands the proxy an IP address, and it can only take credentials
 *       from a JVM-wide {@link java.net.Authenticator}.</li>
 * </ul>
 *
 * <p>Here the proxy receives the <b>host name</b> (SOCKS5 address type 3), so name
 * resolution happens at the proxy, and credentials never leave this instance.</p>
 */
public final class Socks5Transport implements Transport {

    private static final byte VERSION = 5;

    private static final byte METHOD_NONE = 0x00;
    private static final byte METHOD_USERNAME_PASSWORD = 0x02;

    private static final byte COMMAND_CONNECT = 0x01;
    private static final byte ADDRESS_DOMAIN = 0x03;

    private final ProxyConfig proxy;
    private final int timeoutMs;

    public Socks5Transport(ProxyConfig proxy, long timeoutMs) {
        this.proxy = proxy;
        this.timeoutMs = (int) Math.max(500L, Math.min(timeoutMs, Integer.MAX_VALUE));
    }

    @Override
    public Response get(URI uri) throws IOException {
        String host = TunneledRequest.hostOf(uri);
        boolean tls = TunneledRequest.isTls(uri);
        int port = TunneledRequest.portOf(uri, tls);

        Socket socket = new Socket();
        try {
            socket.connect(proxy.address(), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            handshake(socket, host, port);
            return TunneledRequest.execute(socket, uri, host, port, tls, timeoutMs);
        } finally {
            TunneledRequest.closeQuietly(socket);
        }
    }

    /** Greeting, optional username/password sub-negotiation, then CONNECT to the target. */
    private void handshake(Socket socket, String host, int port) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        boolean withCredentials = proxy.authenticated();
        out.writeByte(VERSION);
        out.writeByte(withCredentials ? 2 : 1);
        out.writeByte(METHOD_NONE);
        if (withCredentials) {
            out.writeByte(METHOD_USERNAME_PASSWORD);
        }
        out.flush();

        int version = in.readUnsignedByte();
        int method = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("proxy at " + proxy.host() + ":" + proxy.port()
                    + " is not a SOCKS5 proxy (version " + version + ")");
        }
        if (method == (byte) 0xFF) {
            throw new IOException("proxy at " + proxy.host() + ":" + proxy.port()
                    + " accepted none of the offered authentication methods");
        }
        if (method == METHOD_USERNAME_PASSWORD) {
            authenticate(in, out);
        } else if (method != METHOD_NONE) {
            throw new IOException("proxy chose unsupported SOCKS5 authentication method " + method);
        }

        byte[] target = host.getBytes(StandardCharsets.UTF_8);
        if (target.length > 255) {
            throw new IOException("host name too long for SOCKS5: " + host);
        }
        out.writeByte(VERSION);
        out.writeByte(COMMAND_CONNECT);
        out.writeByte(0);
        out.writeByte(ADDRESS_DOMAIN);
        out.writeByte(target.length);
        out.write(target);
        out.writeShort(port);
        out.flush();

        int replyVersion = in.readUnsignedByte();
        int reply = in.readUnsignedByte();
        in.readUnsignedByte(); // reserved
        if (replyVersion != VERSION) {
            throw new IOException("unexpected SOCKS5 reply version " + replyVersion);
        }
        if (reply != 0) {
            throw new IOException("proxy at " + proxy.host() + ":" + proxy.port()
                    + " could not connect to " + host + ":" + port + ": " + replyText(reply));
        }
        skipBoundAddress(in);
    }

    private void authenticate(DataInputStream in, DataOutputStream out) throws IOException {
        if (!proxy.authenticated()) {
            throw new IOException("proxy at " + proxy.host() + ":" + proxy.port()
                    + " requires a username and password, but none are configured");
        }
        byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8);
        byte[] password = proxy.password().getBytes(StandardCharsets.UTF_8);
        if (user.length > 255 || password.length > 255) {
            throw new IOException("SOCKS5 username and password must be at most 255 bytes");
        }
        out.writeByte(0x01);
        out.writeByte(user.length);
        out.write(user);
        out.writeByte(password.length);
        out.write(password);
        out.flush();

        int authVersion = in.readUnsignedByte();
        int status = in.readUnsignedByte();
        if (authVersion != 0x01 || status != 0) {
            throw new IOException("proxy at " + proxy.host() + ":" + proxy.port()
                    + " rejected the username/password (status " + status + ")");
        }
    }

    private void skipBoundAddress(DataInputStream in) throws IOException {
        int addressType = in.readUnsignedByte();
        switch (addressType) {
            case 0x01 -> in.skipBytes(4);
            case 0x03 -> in.skipBytes(in.readUnsignedByte());
            case 0x04 -> in.skipBytes(16);
            default -> throw new IOException("unknown SOCKS5 address type in reply: " + addressType);
        }
        in.readUnsignedShort();
    }

    private static String replyText(int reply) {
        return switch (reply) {
            case 1 -> "general SOCKS server failure";
            case 2 -> "connection not allowed by ruleset";
            case 3 -> "network unreachable";
            case 4 -> "host unreachable";
            case 5 -> "connection refused";
            case 6 -> "TTL expired";
            case 7 -> "command not supported";
            case 8 -> "address type not supported";
            default -> "reply code " + reply;
        };
    }
}
