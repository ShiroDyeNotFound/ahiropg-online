package com.ahiropg.online.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The offline UUID rule used by Velocity and Bukkit:
 * <pre>UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(UTF_8))</pre>
 *
 * <p>Note that this is case sensitive: the canonical spelling of a name is the one
 * the client logged in with. For example {@code NapCatt_owo} and {@code Napcatt_owo}
 * produce two different offline UUIDs.</p>
 */
public final class OfflineUuids {

    private static final String PREFIX = "OfflinePlayer:";

    private OfflineUuids() {
    }

    public static UUID of(String username) {
        return UUID.nameUUIDFromBytes((PREFIX + username).getBytes(StandardCharsets.UTF_8));
    }
}
