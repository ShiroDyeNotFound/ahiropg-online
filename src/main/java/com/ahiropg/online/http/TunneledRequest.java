package com.ahiropg.online.http;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Shared tail of a tunnelled request: once a proxy has handed us a raw socket to the
 * target, wrap it in TLS when needed, write one HTTP/1.1 GET, and parse the reply.
 *
 * <p>Used by both {@link Socks5Transport} and {@link HttpProxyTransport}, so the two
 * proxy types cannot drift apart in how they speak HTTP.</p>
 */
final class TunneledRequest {

    private TunneledRequest() {
    }

    static Transport.Response execute(Socket socket, URI uri, String host, int port,
                                      boolean tls, int timeoutMs) throws IOException {
        Socket channel = socket;
        if (tls) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            channel = ssl;
        }

        OutputStream out = channel.getOutputStream();
        out.write(buildRequest(uri, host, port).getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        return HttpResponseReader.read(channel.getInputStream());
    }

    static String buildRequest(URI uri, String host, int port) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        boolean defaultPort = (port == 443 && "https".equalsIgnoreCase(uri.getScheme()))
                || (port == 80 && "http".equalsIgnoreCase(uri.getScheme()));
        String hostHeader = defaultPort ? host : host + ":" + port;
        return "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Accept: application/json\r\n"
                + "User-Agent: ahiropg-online\r\n"
                + "Connection: close\r\n"
                + "\r\n";
    }

    static int portOf(URI uri, boolean tls) {
        return uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
    }

    static boolean isTls(URI uri) {
        return !"http".equalsIgnoreCase(uri.getScheme());
    }

    static String hostOf(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("no host in " + uri);
        }
        return host;
    }

    static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do while cleaning up.
        }
    }
}
