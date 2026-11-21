package com.ahiropg.online.http;

import com.ahiropg.online.config.ProxyConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Tunnels one request through an HTTP proxy using {@code CONNECT}.
 *
 * <p>Written by hand rather than delegating to {@link java.net.http.HttpClient} for
 * two reasons found while testing this plugin:</p>
 * <ul>
 *   <li>the client never invokes its {@link java.net.Authenticator} for a {@code CONNECT}
 *       tunnel, so proxy credentials simply do not work: the 407 comes back as the final
 *       response;</li>
 *   <li>credentials would have to be attached through that authenticator, and proxy
 *       credentials are exactly the thing that should not be configured globally.</li>
 * </ul>
 *
 * <p>The handler here sends {@code Proxy-Authorization} up front, connects by host name,
 * and then hands the socket to {@link TunneledRequest}.</p>
 */
public final class HttpProxyTransport implements Transport {

    private final ProxyConfig proxy;
    private final int timeoutMs;

    public HttpProxyTransport(ProxyConfig proxy, long timeoutMs) {
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
            connect(socket, host, port);
            return TunneledRequest.execute(socket, uri, host, port, tls, timeoutMs);
        } finally {
            TunneledRequest.closeQuietly(socket);
        }
    }

    private void connect(Socket socket, String host, int port) throws IOException {
        String authority = host + ":" + port;
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
                .append("Host: ").append(authority).append("\r\n");
        if (proxy.authenticated()) {
            String credentials = proxy.username() + ":" + proxy.password();
            request.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
                    .append("\r\n");
        }
        request.append("\r\n");

        OutputStream out = socket.getOutputStream();
        out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String statusLine = readHead(socket.getInputStream());
        int status = parseStatus(statusLine);

        if (status == 407) {
            throw new IOException("proxy at " + proxy.host() + ":" + proxy.port()
                    + " rejected the credentials (HTTP 407)");
        }
        if (status < 200 || status > 299) {
            throw new IOException("proxy at " + proxy.host() + ":" + proxy.port()
                    + " refused to tunnel to " + host + ":" + port + " (HTTP " + status + ")");
        }
    }

    /**
     * Reads the CONNECT response head byte by byte, so nothing past the final CRLF is
     * consumed: those bytes would be the start of the TLS handshake.
     */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        int b;
        while ((b = in.read()) != -1) {
            buffer.write(b);
            byte[] data = buffer.toByteArray();
            int length = data.length;
            if (length >= 4
                    && data[length - 4] == '\r' && data[length - 3] == '\n'
                    && data[length - 2] == '\r' && data[length - 1] == '\n') {
                return data.length == 0 ? "" : new String(data, StandardCharsets.ISO_8859_1);
            }
        }
        throw new IOException("the proxy closed the connection before answering CONNECT");
    }

    private static int parseStatus(String head) throws IOException {
        int lineEnd = head.indexOf('\r');
        String statusLine = lineEnd >= 0 ? head.substring(0, lineEnd) : head.trim();
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].toUpperCase(Locale.ROOT).startsWith("HTTP/")) {
            throw new IOException("malformed response from the proxy: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("malformed status from the proxy: " + statusLine, e);
        }
    }
}
