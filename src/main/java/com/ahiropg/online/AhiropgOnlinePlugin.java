package com.ahiropg.online;

import com.ahiropg.online.auth.MojangApi;
import com.ahiropg.online.config.Settings;
import com.ahiropg.online.forward.BackendMessenger;
import com.ahiropg.online.listener.IdentityListener;
import com.ahiropg.online.user.UserStore;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * ahiropg-online -- Velocity premium session verification.
 *
 * <p>It does one thing: when a player connects, decide whether this login is a
 * premium session. If it is, use the Mojang UUID; if not, use Velocity's offline
 * UUID. The verdict is forwarded to the backend and the name is recorded in
 * users.yml.</p>
 */
@Plugin(
        id = "ahiropg-online",
        name = "ahiropg-online",
        version = "1.0.0",
        description = "Premium session verification: premium players get their Mojang UUID, "
                + "offline players get Velocity's offline UUID, and the verdict is forwarded to the backend.",
        authors = {"ShiroDyeNotFound"}
)
public final class AhiropgOnlinePlugin {

    private static final String CONFIG_FILE = "config.yml";
    private static final String USERS_FILE = "users.yml";
    private static final String PLUGIN_DESCRIPTOR = "velocity-plugin.json";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Settings settings;
    private UserStore users;
    private MojangApi mojang;

    @Inject
    public AhiropgOnlinePlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            copyDefaultConfig();

            this.settings = Settings.load(dataDirectory.resolve(CONFIG_FILE), logger);
            this.users = new UserStore(dataDirectory.resolve(USERS_FILE), logger);
            this.users.load();
        } catch (IOException e) {
            logger.error("Initialisation failed, the plugin will not take effect: {}", e.getMessage(), e);
            return;
        }

        warnOnVersionMismatch();

        this.mojang = new MojangApi(settings.mojangTimeoutMs(), settings.mojangCacheMinutes(), logger);
        BackendMessenger messenger = new BackendMessenger(proxy, settings, logger);
        messenger.registerChannel();

        proxy.getEventManager().register(this,
                new IdentityListener(proxy, logger, settings, users, mojang, messenger));

        // users.yml is not written on every login; flush the accumulated changes every 5 seconds.
        proxy.getScheduler()
                .buildTask(this, users::flushIfDirty)
                .repeat(5L, TimeUnit.SECONDS)
                .schedule();

        boolean proxyOnline = proxy.getConfiguration().isOnlineMode();
        logger.info("ahiropg-online loaded: policy={}, proxy online-mode={}, records file={}",
                settings.policy(), proxyOnline, dataDirectory.resolve(USERS_FILE));

        if (settings.policy() == Settings.Policy.HYBRID) {
            logger.info("HYBRID: a client claiming to be premium (1.20.2+ always sends a UUID at login) "
                            + "is required to pass premium session verification; offline clients join with "
                            + "their offline UUID (allow-offline-connections={})",
                    settings.allowOfflineConnections());
            if (!proxyOnline) {
                logger.info("The proxy is in offline mode, so this plugin calls forceOnlineMode() on premium "
                        + "connections and lets Velocity run its own session verification.");
            }
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (users != null) {
            users.save();
            logger.info("users.yml saved");
        }
        if (mojang != null) {
            mojang.close();
        }
    }

    /**
     * The annotation's version is a compile-time constant and cannot be templated, while
     * velocity-plugin.json takes its version from the pom's {@code revision}. If a build
     * skipped resource filtering, or the two were edited by hand, point it out once at
     * startup instead of shipping a jar that reports two different versions.
     */
    private void warnOnVersionMismatch() {
        try (InputStream input = AhiropgOnlinePlugin.class.getResourceAsStream("/" + PLUGIN_DESCRIPTOR)) {
            if (input == null) {
                return;
            }
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String descriptorVersion = JsonParser.parseString(json).getAsJsonObject().get("version").getAsString();
            String annotationVersion = getClass().getAnnotation(Plugin.class).version();
            if (!annotationVersion.equals(descriptorVersion)) {
                logger.warn("Version mismatch: @Plugin declares {} but {} declares {}. "
                                + "Bump <revision> in pom.xml and rebuild so the descriptor is filtered.",
                        annotationVersion, PLUGIN_DESCRIPTOR, descriptorVersion);
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read {}: {}", PLUGIN_DESCRIPTOR, e.toString());
        }
    }

    /** On first start, copies the bundled config.yml into the plugin directory. Never overwrites. */
    private void copyDefaultConfig() throws IOException {
        Path target = dataDirectory.resolve(CONFIG_FILE);
        if (Files.exists(target)) {
            return;
        }
        try (InputStream input = AhiropgOnlinePlugin.class.getResourceAsStream("/" + CONFIG_FILE)) {
            if (input == null) {
                throw new IOException(CONFIG_FILE + " is missing from the jar");
            }
            Files.copy(input, target);
            logger.info("Wrote default config to {}", target);
        }
    }
}
