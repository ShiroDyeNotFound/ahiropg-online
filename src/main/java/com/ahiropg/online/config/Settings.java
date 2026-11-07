package com.ahiropg.online.config;

import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * In-memory model of config.yml. Any missing key falls back to the default baked
 * into the code, so a broken config can never stop the plugin from starting.
 */
public record Settings(
        Policy policy,
        boolean allowOfflineConnections,
        LegacyClientPolicy legacyClientPolicy,
        UnavailablePolicy mojangUnavailablePolicy,
        String channel,
        boolean forwardOnServerSwitch,
        boolean overrideUuid,
        long mojangTimeoutMs,
        long mojangCacheMinutes,
        boolean offlineNoticeEnabled,
        String offlineNoticeMessage,
        String offlineDeniedMessage
) {

    public enum Policy {
        /** Decide premium/offline per connection (mixed-mode network). */
        HYBRID,
        /** Do not interfere; follow velocity.toml online-mode. */
        PROXY
    }

    /** Clients 1.19.2 and older never declare a UUID, so this is the fallback. */
    public enum LegacyClientPolicy {
        OFFLINE,
        PREMIUM
    }

    /** Where to fall back when the Mojang lookup fails. */
    public enum UnavailablePolicy {
        OFFLINE,
        PREMIUM
    }

    public static final String DEFAULT_CHANNEL = "ahiropg:online";

    /** Minecraft plugin messaging channel names: lowercase namespace:name. */
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");

    public static Settings load(Path file, Logger logger) throws IOException {
        CommentedConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().load();
        } catch (ConfigurateException e) {
            throw new IOException("Failed to read config.yml: " + e.getMessage(), e);
        }

        String channel = root.node("forward", "channel").getString(DEFAULT_CHANNEL);
        if (channel == null || !CHANNEL_PATTERN.matcher(channel).matches()) {
            logger.warn("config.yml forward.channel={} is not a valid channel name "
                    + "(expected namespace:name), falling back to {}", channel, DEFAULT_CHANNEL);
            channel = DEFAULT_CHANNEL;
        }

        return new Settings(
                parseEnum(Policy.class, root.node("session-policy").getString("HYBRID"),
                        Policy.HYBRID, logger, "session-policy"),
                root.node("hybrid", "allow-offline-connections").getBoolean(true),
                parseEnum(LegacyClientPolicy.class, root.node("hybrid", "legacy-client-policy").getString("offline"),
                        LegacyClientPolicy.OFFLINE, logger, "hybrid.legacy-client-policy"),
                parseEnum(UnavailablePolicy.class, root.node("hybrid", "mojang-unavailable-policy").getString("offline"),
                        UnavailablePolicy.OFFLINE, logger, "hybrid.mojang-unavailable-policy"),
                channel,
                root.node("forward", "on-server-switch").getBoolean(true),
                root.node("forward", "override-uuid").getBoolean(true),
                Math.max(500L, root.node("mojang", "timeout-ms").getLong(3000L)),
                Math.max(1L, root.node("mojang", "cache-minutes").getLong(60L)),
                root.node("offline-notice", "enabled").getBoolean(true),
                root.node("offline-notice", "message").getString(
                        "&eYou previously joined with a premium session, but this login did not verify as premium "
                                + "(expired session, or your launcher is in offline mode). "
                                + "You are joining as an offline player this time."),
                root.node("offline-notice", "denied-message").getString(
                        "&cThis server only accepts premium (Mojang-authenticated) logins.")
        );
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback, Logger logger, String key) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            logger.warn("config.yml {}={} is not recognised, using {}", key, raw, fallback);
            return fallback;
        }
    }
}
