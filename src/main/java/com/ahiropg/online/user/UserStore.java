package com.ahiropg.online.user;

import com.ahiropg.online.auth.OfflineUuids;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reading and writing users.yml.
 *
 * <p>The file holds, per player, the identity the plugin decided on for their most
 * recent login: premium players carry the Mojang UUID, offline players carry the
 * offline UUID.</p>
 *
 * <p>It is not written on every login. Modifications mark the store dirty and a
 * scheduled task flushes it every 5 seconds, to avoid constant IO; shutdown forces
 * a final write. Writing goes through a temporary file plus an atomic move, so a
 * crash mid-write can never leave half a file behind.</p>
 */
public final class UserStore {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String HEADER = """
            # ==========================================================================
            #  ahiropg-online -- player identity records
            #  Maintained automatically by the plugin; editing it has no effect.
            #
            #  premium       whether this account has ever passed premium session verification
            #  premium-uuid  the Mojang UUID (line omitted when it is not known yet)
            #  offline-uuid  the UUID derived by Velocity's offline rule
            #  last-uuid     the UUID forwarded to the backend most recently
            #  last-mode     the identity actually used for the most recent login
            # ==========================================================================
            """;

    /**
     * One player record.
     * {@code premiumUuid} can be null before the Mojang lookup has come back.
     */
    public record Record(String name,
                         boolean premium,
                         UUID premiumUuid,
                         UUID offlineUuid,
                         UUID lastUuid,
                         String lastMode,
                         String firstSeen,
                         String lastSeen) {
    }

    private final Path file;
    private final Logger logger;
    private final YamlConfigurationLoader loader;
    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Object ioLock = new Object();

    private volatile boolean dirty;

    public UserStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.loader = YamlConfigurationLoader.builder()
                .path(file)
                .nodeStyle(NodeStyle.BLOCK)
                .indent(2)
                .build();
    }

    public void load() throws IOException {
        CommentedConfigurationNode root;
        try {
            root = loader.load();
        } catch (ConfigurateException e) {
            Path broken = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            try {
                Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
                logger.error("Failed to parse users.yml ({}), backed it up as {} and starting from empty records",
                        e.getMessage(), broken.getFileName());
            } catch (IOException moveError) {
                logger.error("Failed to parse users.yml ({}) and backing it up also failed ({}); "
                                + "starting from empty records",
                        e.getMessage(), moveError.toString());
            }
            return;
        }

        for (Map.Entry<Object, ? extends CommentedConfigurationNode> entry : root.node("players").childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            CommentedConfigurationNode node = entry.getValue();
            String name = node.node("name").getString(key);
            if (name == null || name.isBlank()) {
                name = key;
            }
            records.put(key.toLowerCase(Locale.ROOT), new Record(
                    name,
                    node.node("premium").getBoolean(false),
                    parseUuid(node.node("premium-uuid").getString("")),
                    parseUuid(node.node("offline-uuid").getString("")),
                    parseUuid(node.node("last-uuid").getString("")),
                    node.node("last-mode").getString("offline"),
                    node.node("first-seen").getString(""),
                    node.node("last-seen").getString("")
            ));
        }
        logger.info("Loaded users.yml with {} player record(s)", records.size());
    }

    public Optional<Record> get(String username) {
        return Optional.ofNullable(records.get(username.toLowerCase(Locale.ROOT)));
    }

    /**
     * Records one login. Only updates memory and marks the store dirty; the actual
     * write happens in {@link #flushIfDirty()}.
     *
     * @param premiumNow whether this login passed premium session verification
     * @param lastUuid   the UUID actually forwarded to the backend this time
     */
    public void touch(String username, boolean premiumNow, UUID lastUuid) {
        String key = username.toLowerCase(Locale.ROOT);
        String now = LocalDateTime.now().format(TIME_FORMAT);
        records.compute(key, (ignored, old) -> {
            boolean premium = premiumNow || (old != null && old.premium());
            UUID premiumUuid = premiumNow ? lastUuid : (old == null ? null : old.premiumUuid());
            UUID offlineUuid = old != null && old.offlineUuid() != null ? old.offlineUuid() : OfflineUuids.of(username);
            String firstSeen = old == null || old.firstSeen().isBlank() ? now : old.firstSeen();
            return new Record(username, premium, premiumUuid, offlineUuid, lastUuid,
                    premiumNow ? "premium" : "offline", firstSeen, now);
        });
        dirty = true;
    }

    /** Once the Mojang lookup returns, fill in / correct the premium UUID. */
    public void setPremiumUuid(String username, UUID premiumUuid) {
        String key = username.toLowerCase(Locale.ROOT);
        records.computeIfPresent(key, (ignored, old) ->
                new Record(old.name(), true, premiumUuid, old.offlineUuid(), old.lastUuid(),
                        old.lastMode(), old.firstSeen(), old.lastSeen()));
        dirty = true;
    }

    public void flushIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** Forces a write. A failure is only logged; it never affects players. */
    public void save() {
        synchronized (ioLock) {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(temp, render(), StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                dirty = false;
            } catch (IOException e) {
                logger.error("Failed to write users.yml: {}", e.toString());
            }
        }
    }

    private String render() {
        List<Record> sorted = new ArrayList<>(records.values());
        sorted.sort(Comparator.comparing(record -> record.name().toLowerCase(Locale.ROOT)));

        StringBuilder out = new StringBuilder(HEADER);
        if (sorted.isEmpty()) {
            out.append('\n').append("players: {}").append('\n');
            return out.toString();
        }

        out.append('\n').append("players:").append('\n');
        for (Record record : sorted) {
            out.append("  ").append(quote(record.name().toLowerCase(Locale.ROOT))).append(':').append('\n');
            out.append("    name: ").append(quote(record.name())).append('\n');
            out.append("    premium: ").append(record.premium()).append('\n');
            if (record.premiumUuid() != null) {
                out.append("    premium-uuid: ").append(quote(record.premiumUuid().toString())).append('\n');
            }
            if (record.offlineUuid() != null) {
                out.append("    offline-uuid: ").append(quote(record.offlineUuid().toString())).append('\n');
            }
            if (record.lastUuid() != null) {
                out.append("    last-uuid: ").append(quote(record.lastUuid().toString())).append('\n');
            }
            out.append("    last-mode: ").append(quote(record.lastMode())).append('\n');
            out.append("    first-seen: ").append(quote(record.firstSeen())).append('\n');
            out.append("    last-seen: ").append(quote(record.lastSeen())).append('\n');
        }
        return out.toString();
    }

    /** A YAML single-quoted scalar: doubling any inner quote is the only escape needed. */
    private static String quote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
