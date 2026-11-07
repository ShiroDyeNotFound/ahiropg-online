package com.ahiropg.online.forward;

import com.ahiropg.online.auth.Identity;
import com.ahiropg.online.config.Settings;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Pushes identity information to the backend server.
 *
 * <p>There are two independent ways the backend learns the UUID:</p>
 * <ol>
 *   <li><b>Login handshake</b>: when Velocity connects to a backend it forwards the
 *       player's GameProfile, UUID included. The plugin already sets that UUID to the
 *       correct value during {@code GameProfileRequestEvent}, so the backend has the
 *       right UUID the moment the player joins.</li>
 *   <li><b>Plugin message</b>: once the player is on a backend we push an extra
 *       {@code ahiropg:online} message carrying name, UUID and premium flag, so a
 *       backend plugin can double-check or record it in its own database.</li>
 * </ol>
 *
 * <p>Payload format (UTF is {@code DataOutputStream#writeUTF}, i.e. a 2 byte length
 * followed by the bytes):</p>
 * <pre>
 * byte    protocol-version (currently 1)
 * UTF     player-name
 * UTF     uuid (dashed, canonical form)
 * boolean premium
 * </pre>
 */
public final class BackendMessenger {

    /** Payload protocol version, so the backend can stay compatible if the format changes. */
    public static final byte PROTOCOL_VERSION = 1;

    private final ProxyServer proxy;
    private final Settings settings;
    private final Logger logger;
    private final MinecraftChannelIdentifier channel;

    public BackendMessenger(ProxyServer proxy, Settings settings, Logger logger) {
        this.proxy = proxy;
        this.settings = settings;
        this.logger = logger;
        this.channel = MinecraftChannelIdentifier.from(settings.channel());
    }

    public void registerChannel() {
        proxy.getChannelRegistrar().register(channel);
        logger.info("Registered plugin messaging channel {}", channel.getId());
    }

    /** Pushes this login's identity to the backend the player is currently on. */
    public void send(Player player, Identity identity) {
        if (!settings.forwardOnServerSwitch()) {
            return;
        }
        Optional<ServerConnection> connection = player.getCurrentServer();
        if (connection.isEmpty()) {
            return;
        }
        byte[] payload = encode(identity);
        boolean accepted = connection.get().sendPluginMessage(channel, payload);
        if (!accepted) {
            logger.warn("Backend {} refused the identity push for {} (is the channel registered there?)",
                    connection.get().getServerInfo().getName(), identity.username());
        }
    }

    public static byte[] encode(Identity identity) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(PROTOCOL_VERSION);
            out.writeUTF(identity.username());
            out.writeUTF(identity.uuid().toString());
            out.writeBoolean(identity.premium());
        } catch (IOException e) {
            // ByteArrayOutputStream never throws IO exceptions.
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }
}
