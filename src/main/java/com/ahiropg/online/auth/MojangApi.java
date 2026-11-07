package com.ahiropg.online.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mojang name to premium UUID lookup.
 *
 * <p>It does exactly one thing: given a player name, tell the caller whether that
 * name is a premium account and what its UUID is. It is <em>not</em> session
 * verification: that is done by Velocity during the online-mode handshake. The
 * UUID found here is used for the users.yml record and for a consistency check
 * against the UUID the session produced.</p>
 *
 * <p>Every request is asynchronous ({@link HttpClient#sendAsync}), so Velocity's
 * network threads are never blocked.</p>
 */
public final class MojangApi implements AutoCloseable {

    private static final String LOOKUP_URL = "https://api.mojang.com/users/profiles/minecraft/";

    /** A premium account as recorded by Mojang. */
    public record Profile(UUID uuid, String name) {
    }

    private record CacheEntry(Optional<Profile> profile, long expiresAt) {
    }

    private final HttpClient http;
    private final ExecutorService executor;
    private final Duration timeout;
    private final long cacheMillis;
    private final Logger logger;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public MojangApi(long timeoutMs, long cacheMinutes, Logger logger) {
        this.logger = logger;
        this.timeout = Duration.ofMillis(Math.max(500L, timeoutMs));
        this.cacheMillis = TimeUnit.MINUTES.toMillis(Math.max(1L, cacheMinutes));
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ahiropg-online-mojang");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .executor(executor)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Look up the premium account for a name.
     *
     * <p>Returns {@code Optional.empty()} when the name is not a premium account
     * (Mojang answers 204/404). Network errors and rate limiting complete the
     * returned future exceptionally, leaving the fallback decision to the caller.</p>
     */
    public CompletableFuture<Optional<Profile>> lookup(String username) {
        String key = username.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return CompletableFuture.completedFuture(cached.profile());
        }

        URI uri = URI.create(LOOKUP_URL + URLEncoder.encode(username, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parse(username, response.statusCode(), response.body()))
                .thenApply(profile -> {
                    cache.put(key, new CacheEntry(profile, System.currentTimeMillis() + cacheMillis));
                    return profile;
                });
    }

    private Optional<Profile> parse(String username, int status, String body) {
        if (status == 204 || status == 404) {
            // The name does not exist, or is not a premium account.
            return Optional.empty();
        }
        if (status == 429) {
            throw new IllegalStateException("Mojang API rate limited the request (HTTP 429)");
        }
        if (status != 200) {
            throw new IllegalStateException("Mojang API returned HTTP " + status);
        }
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        if (!json.has("id")) {
            throw new IllegalStateException("Mojang API response has no id field: " + body);
        }
        UUID uuid = fromUndashed(json.get("id").getAsString());
        String canonicalName = json.has("name") ? json.get("name").getAsString() : username;
        if (!canonicalName.equals(username)) {
            // Case differs from what the client sent. Only a notice, never a failure:
            // offline UUIDs are derived from the name, and case changes the result.
            logger.debug("Canonical spelling of {} is {} according to Mojang", username, canonicalName);
        }
        return Optional.of(new Profile(uuid, canonicalName));
    }

    /** Mojang returns an undashed 32 character hex string. */
    private static UUID fromUndashed(String raw) {
        String value = raw.trim();
        if (value.length() != 32) {
            throw new IllegalArgumentException("Malformed UUID from Mojang: " + raw);
        }
        return UUID.fromString(value.substring(0, 8) + "-"
                + value.substring(8, 12) + "-"
                + value.substring(12, 16) + "-"
                + value.substring(16, 20) + "-"
                + value.substring(20));
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                logger.debug("Mojang lookup executor did not shut down in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
