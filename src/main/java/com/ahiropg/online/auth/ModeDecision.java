package com.ahiropg.online.auth;

/** What the plugin wants done with one connecting client. */
public enum ModeDecision {

    /** Require a premium session for this connection (only needed on an offline-mode proxy). */
    FORCE_ONLINE,

    /** Let this connection join as offline (only needed on an online-mode proxy). */
    FORCE_OFFLINE,

    /** Reject the login. */
    DENY,

    /** Reject the login because this account has joined as premium before and is locked to it. */
    DENY_PREMIUM_REQUIRED,

    /** Do not interfere; let Velocity follow velocity.toml online-mode. */
    LEAVE
}
