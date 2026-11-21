package com.ahiropg.online.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Direct requests through the JDK HTTP client, used when no proxy is configured.
 *
 * <p>Proxy traffic deliberately does not go through this client: it silently ignores
 * SOCKS proxies (JDK-8214513) and never authenticates a {@code CONNECT} tunnel, so
 * both proxy types are handled by {@link Socks5Transport} and
 * {@link HttpProxyTransport} instead.</p>
 */
public final class HttpClientTransport implements Transport {

    private final HttpClient client;
    private final Duration timeout;

    public HttpClientTransport(long timeoutMs) {
        this.timeout = Duration.ofMillis(Math.max(500L, timeoutMs));
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Response get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", "ahiropg-online")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for Mojang", e);
        }
    }
}
