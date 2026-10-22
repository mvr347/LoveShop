package dev.lovelace.loveshops.npc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the plugin's configured texts into the components the NPC contract takes. Citizens used to receive the
 * same texts as legacy strings, so the conversion keeps colours exactly as the old
 * {@code ChatColor.translateAlternateColorCodes} path produced them.
 */
public final class NpcText {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private NpcText() {
    }

    /** A config string with {@code &} colour codes (also {@code &#rrggbb} and {@code &x&r&r&g&g&b&b}). */
    public static Component amp(String text) {
        return AMPERSAND.deserialize(text == null ? "" : text);
    }

    /** A string that may already carry section-sign colour codes (names stored in the database). */
    public static Component section(String text) {
        return SECTION.deserialize(text == null ? "" : text);
    }

    /** Plain text of a {@code &}-coded config string: what an NPC with that name is called without styling. */
    public static String plainAmp(String text) {
        return PlainTextComponentSerializer.plainText().serialize(amp(text));
    }

    /** The non-blank lines of a multi-line config string, each as a component. */
    public static List<Component> ampLines(String text) {
        List<Component> lines = new ArrayList<>();
        if (text == null) return lines;
        for (String line : text.split("\\r?\\n")) {
            if (!line.isBlank()) lines.add(amp(line));
        }
        return lines;
    }
}
