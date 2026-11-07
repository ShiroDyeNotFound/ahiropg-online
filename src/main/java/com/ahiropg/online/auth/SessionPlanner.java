package com.ahiropg.online.auth;

import com.ahiropg.online.config.Settings;

import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a connecting client gets a premium or an offline identity.
 * Pure function: no network, no Velocity objects, so it can be tested on its own.
 *
 * <h2>Why the name alone is not enough</h2>
 * Deciding by "does this name exist on Mojang" conflates two very different
 * situations: a premium account being impersonated by a cracked client, and a
 * premium player who happens to be logging in offline. It also permanently locks
 * out a regular player whose name happens to match a premium account.
 *
 * <h2>The real signal: the UUID the client declares</h2>
 * Since 1.20.2 a client always sends its own UUID in LoginStart, exposed as
 * {@code PreLoginEvent#getUniqueId()}:
 * <ul>
 *   <li>premium launcher with a premium login -&gt; the account's real Mojang UUID</li>
 *   <li>offline login / cracked launcher -&gt; the offline UUID it computed itself
 *       (the v3 UUID of {@code OfflinePlayer:<name>})</li>
 *   <li>third-party auth (authlib sites) -&gt; that site's own UUID</li>
 * </ul>
 * So "this client means to log in as premium" is decidable without guessing and
 * without any network call:
 * <ol>
 *   <li>declared UUID == offline UUID -&gt; clearly an offline login -&gt; allow as offline
 *       (<b>a name-clashing regular player takes exactly this path</b>)</li>
 *   <li>declared UUID == the premium UUID of that name -&gt; hand it back to Velocity for
 *       real verification: passing yields the premium UUID, failing means an impostor
 *       and Velocity kicks him. Forging this UUID only hurts the forger.</li>
 *   <li>anything else (old client without a UUID, third-party site UUID, name unknown
 *       to Mojang) -&gt; fall back per configuration; by default nobody is kicked</li>
 * </ol>
 */
public final class SessionPlanner {

    private SessionPlanner() {
    }

    /**
     * @param username     the name the client logged in with (original case matters,
     *                     the offline UUID is derived from it)
     * @param declaredUuid the UUID the client declared, null on clients 1.19.2 and older
     * @param lookup       the premium account for that name on Mojang, if any
     * @param lookupFailed whether the Mojang lookup itself failed (timeout / rate limit / network)
     * @param proxyOnline  online-mode as configured in the proxy's velocity.toml
     */
    public static ModeDecision decide(String username,
                                      UUID declaredUuid,
                                      Optional<MojangApi.Profile> lookup,
                                      boolean lookupFailed,
                                      boolean proxyOnline,
                                      Settings settings) {

        if (declaredUuid != null) {
            if (declaredUuid.equals(OfflineUuids.of(username))) {
                // 1) The client computed the offline UUID itself: it means to join offline.
                return offline(settings, proxyOnline);
            }
            if (lookup.isPresent() && declaredUuid.equals(lookup.get().uuid())) {
                // 2) The client claims to be the premium account of this name -> let Velocity verify.
                return online(settings, proxyOnline);
            }
        }

        if (declaredUuid == null) {
            // 1.19.2 and older never declare a UUID, so intent is unknown. Fall back.
            return settings.legacyClientPolicy() == Settings.LegacyClientPolicy.PREMIUM
                    ? online(settings, proxyOnline)
                    : offline(settings, proxyOnline);
        }

        if (lookupFailed) {
            // 3) No information: degrade per configuration. Default is offline, because
            //    skipping verification is better than kicking a legitimate player.
            return settings.mojangUnavailablePolicy() == Settings.UnavailablePolicy.PREMIUM
                    ? online(settings, proxyOnline)
                    : offline(settings, proxyOnline);
        }

        // 4) A third-party auth site's UUID: treat it as offline.
        return offline(settings, proxyOnline);
    }

    private static ModeDecision offline(Settings settings, boolean proxyOnline) {
        if (!settings.allowOfflineConnections()) {
            return ModeDecision.DENY;
        }
        // On an offline-mode proxy this is already the default, nothing to force.
        return proxyOnline ? ModeDecision.FORCE_OFFLINE : ModeDecision.LEAVE;
    }

    private static ModeDecision online(Settings settings, boolean proxyOnline) {
        // On an online-mode proxy this is already the default, nothing to force.
        return proxyOnline ? ModeDecision.LEAVE : ModeDecision.FORCE_ONLINE;
    }
}
