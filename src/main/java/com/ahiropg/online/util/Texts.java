package com.ahiropg.online.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Turns text from the config into a Component.
 * Supports MiniMessage ({@code <yellow>text</yellow>}) and legacy colour codes
 * ({@code &a}, {@code §a}).
 */
public final class Texts {

    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private Texts() {
    }

    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        if (raw.indexOf('\u00a7') >= 0) {
            return SECTION.deserialize(raw);
        }
        int open = raw.indexOf('<');
        if (open >= 0 && raw.indexOf('>', open) > open) {
            return MiniMessage.miniMessage().deserialize(raw);
        }
        return AMPERSAND.deserialize(raw);
    }
}
