package com.ahiropg.online.auth;

import java.util.UUID;

/**
 * The identity worked out for one login.
 *
 * @param username          the player name, in the original case the client logged in with
 * @param uuid              the UUID forwarded to the backend: the Mojang UUID for a premium
 *                          player, the offline UUID otherwise
 * @param premium           whether this login passed premium session verification (done by
 *                          Velocity; the plugin only reads the result)
 * @param previouslyPremium whether this account has ever joined as premium before, used to
 *                          tell the player their session probably dropped
 */
public record Identity(String username, UUID uuid, boolean premium, boolean previouslyPremium) {
}
