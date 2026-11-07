package com.ahiropg.online.listener;

import com.ahiropg.online.auth.Identity;
import com.ahiropg.online.auth.ModeDecision;
import com.ahiropg.online.auth.MojangApi;
import com.ahiropg.online.auth.OfflineUuids;
import com.ahiropg.online.auth.SessionPlanner;
import com.ahiropg.online.config.Settings;
import com.ahiropg.online.forward.BackendMessenger;
import com.ahiropg.online.user.UserStore;
import com.ahiropg.online.util.Texts;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The main verification flow.
 *
 * <pre>
 * PreLoginEvent            decide whether this connection goes premium or offline
 *                          (offline-intent connections never wait on the network; only
 *                          premium claims trigger a Mojang lookup)
 *   |
 *   v
 * GameProfileRequestEvent  the actual verdict: isOnlineMode() means "did this login pass
 *                          premium session verification"
 *                          premium -> the UUID the session produced
 *                          offline -> Velocity's offline UUID
 *                          also updates users.yml in memory and fills in the Mojang
 *                          canonical UUID asynchronously
 *   |
 *   v
 * PostLoginEvent           players who joined premium before but are offline now get a
 *                          "your session probably dropped" notice
 *   |
 *   v
 * ServerConnectedEvent     push the identity to the backend
 * </pre>
 */
public final class IdentityListener {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Settings settings;
    private final UserStore users;
    private final MojangApi mojang;
    private final BackendMessenger messenger;

    /** Verdicts for players currently online, keyed by lowercase name. */
    private final Map<String, Identity> identities = new ConcurrentHashMap<>();

    public IdentityListener(ProxyServer proxy, Logger logger, Settings settings,
                            UserStore users, MojangApi mojang, BackendMessenger messenger) {
        this.proxy = proxy;
        this.logger = logger;
        this.settings = settings;
        this.users = users;
        this.mojang = mojang;
        this.messenger = messenger;
    }

    // ------------------------------------------------------------------
    // 1. Login mode
    // ------------------------------------------------------------------
    @Subscribe
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.resumeWhenComplete(decideMode(event));
    }

    private CompletableFuture<Void> decideMode(PreLoginEvent event) {
        if (settings.policy() != Settings.Policy.HYBRID) {
            return CompletableFuture.completedFuture(null);
        }
        // Another plugin already denied it or already forced offline mode; stay out of the way.
        if (!event.getResult().isAllowed() || event.getResult().isForceOfflineMode()) {
            return CompletableFuture.completedFuture(null);
        }

        String username = event.getUsername();
        UUID declared = event.getUniqueId();
        boolean proxyOnline = proxy.getConfiguration().isOnlineMode();

        // Offline-intent connections (they declared the offline UUID) and old clients
        // do not need a Mojang lookup at all.
        if (declared == null || declared.equals(OfflineUuids.of(username))) {
            apply(event, username, declared, Optional.empty(), false, proxyOnline);
            return CompletableFuture.completedFuture(null);
        }

        try {
            return mojang.lookup(username)
                    .thenAccept(profile -> apply(event, username, declared, profile, false, proxyOnline))
                    .exceptionally(error -> {
                        logger.warn("Mojang lookup failed for {}, falling back per configuration: {}",
                                username, rootMessage(error));
                        apply(event, username, declared, Optional.empty(), true, proxyOnline);
                        return null;
                    });
        } catch (RuntimeException e) {
            // In rare cases lookup itself throws (the HTTP client was already closed, say).
            // Never let that break the login flow.
            logger.warn("Mojang lookup threw for {}, falling back per configuration: {}", username, e.toString());
            apply(event, username, declared, Optional.empty(), true, proxyOnline);
            return CompletableFuture.completedFuture(null);
        }
    }

    private void apply(PreLoginEvent event, String username, UUID declared,
                       Optional<MojangApi.Profile> lookup, boolean lookupFailed, boolean proxyOnline) {
        ModeDecision decision = SessionPlanner.decide(username, declared, lookup, lookupFailed, proxyOnline, settings);
        switch (decision) {
            case FORCE_ONLINE -> {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                logger.info("{} claims a premium client (UUID {}) -> handing over to Velocity "
                        + "for premium session verification", username, describe(declared));
            }
            case FORCE_OFFLINE -> {
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                logger.info("{} allowed through as offline (UUID {})", username, describe(declared));
            }
            case DENY -> {
                event.setResult(PreLoginEvent.PreLoginComponentResult
                        .denied(Texts.parse(settings.offlineDeniedMessage())));
                logger.info("{} has no premium session and this server rejects offline connections -> denied (UUID {})",
                        username, describe(declared));
            }
            case LEAVE -> logger.debug("{} left to the proxy default login mode (UUID {})",
                    username, describe(declared));
        }
    }

    // ------------------------------------------------------------------
    // 2. The verdict
    // ------------------------------------------------------------------
    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        String username = event.getUsername();
        boolean premium = event.isOnlineMode();

        // Check history first: has this account ever joined premium before? This decides
        // whether we warn the player that their session probably dropped.
        boolean previouslyPremium = users.get(username).map(UserStore.Record::premium).orElse(false);

        GameProfile profile = event.getGameProfile();
        UUID sessionUuid = profile.getId();
        UUID offlineUuid = OfflineUuids.of(username);
        UUID uuid = premium ? sessionUuid : offlineUuid;

        if (settings.overrideUuid() && !uuid.equals(sessionUuid)) {
            event.setGameProfile(profile.withId(uuid));
        }

        Identity identity = new Identity(username, uuid, premium, previouslyPremium);
        identities.put(key(username), identity);
        users.touch(username, premium, uuid);

        if (premium) {
            logger.info("{} passed premium session verification -> forwarding premium UUID {}", username, uuid);
            confirmWithMojang(username, sessionUuid);
        } else {
            logger.info("{} did not pass premium session verification -> forwarding offline UUID {}{}", username, uuid,
                    previouslyPremium ? " (this account used to join as premium)" : "");
        }
    }

    /** Fills in / corrects the premium UUID recorded in users.yml using Mojang's canonical UUID. */
    private void confirmWithMojang(String username, UUID sessionUuid) {
        try {
            mojang.lookup(username).whenComplete((profile, error) -> {
                if (error != null) {
                    logger.warn("{} passed session verification but the follow-up Mojang lookup failed: {}",
                            username, rootMessage(error));
                    return;
                }
                if (profile.isEmpty()) {
                    logger.warn("{} passed session verification but Mojang does not know this name", username);
                    return;
                }
                UUID official = profile.get().uuid();
                if (!official.equals(sessionUuid)) {
                    logger.warn("{}: the session reported UUID {} but Mojang records {}; users.yml keeps Mojang's",
                            username, sessionUuid, official);
                }
                users.setPremiumUuid(username, official);
            });
        } catch (RuntimeException e) {
            logger.warn("Follow-up Mojang lookup threw: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 3. "Your session probably dropped" notice
    // ------------------------------------------------------------------
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        Identity identity = identities.get(key(player.getUsername()));
        if (identity == null || identity.premium() || !identity.previouslyPremium()
                || !settings.offlineNoticeEnabled()) {
            return;
        }
        player.sendMessage(Texts.parse(fill(settings.offlineNoticeMessage(), player.getUsername(), identity.uuid())));
    }

    // ------------------------------------------------------------------
    // 4. Push to the backend
    // ------------------------------------------------------------------
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Identity identity = identities.get(key(event.getPlayer().getUsername()));
        if (identity == null) {
            return;
        }
        messenger.send(event.getPlayer(), identity);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        identities.remove(key(event.getPlayer().getUsername()));
    }

    private static String fill(String template, String username, UUID uuid) {
        return template.replace("%player%", username).replace("%uuid%", uuid.toString());
    }

    private static String describe(UUID declared) {
        return declared == null ? "not provided by the client (1.19.2 or older)" : declared.toString();
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
