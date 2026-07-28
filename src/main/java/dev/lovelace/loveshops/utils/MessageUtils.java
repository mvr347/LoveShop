package dev.lovelace.loveshops.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

public final class MessageUtils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private MessageUtils() {}

    public static Component parse(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        if (input.contains("<") && input.contains(">")) {
            try {
                return MINI_MESSAGE.deserialize(input);
            } catch (Exception ignored) {}
        }
        return LEGACY_AMPERSAND.deserialize(input);
    }

    public static void sendMessage(CommandSender sender, String message) {
        if (sender != null && message != null && !message.isEmpty()) {
            sender.sendMessage(parse(message));
        }
    }

    public static String escapeTags(String input) {
        if (input == null || input.isEmpty()) return input;
        return MINI_MESSAGE.escapeTags(input);
    }
}
