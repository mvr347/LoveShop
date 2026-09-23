package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.loveshops.LoveShops;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class MessageUtils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private MessageUtils() {}

    public static Component parse(String input) {
        return parse(null, input);
    }

    /**
     * Same as {@link #parse(String)}, but resolves ItemsAdder currency/font-image placeholders
     * ({@code %img_<tag>%} / {@code %ia_<tag>%}) for a specific viewer first — matches LoveClans'
     * {@code MessageService.parse()} convention: the font-image hook MUST run before MiniMessage
     * deserializes the string, otherwise the literal {@code %img_...%} text is what the tag
     * parser sees.
     */
    public static Component parse(Player player, String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        String resolved = ItemsAdderFontHook.resolve(player, input);
        if (resolved.contains("<") && resolved.contains(">")) {
            try {
                return MINI_MESSAGE.deserialize(resolved);
            } catch (Exception ignored) {}
        }
        return LEGACY_AMPERSAND.deserialize(resolved);
    }

    public static void sendMessage(CommandSender sender, String message) {
        if (sender == null || message == null || message.isEmpty()) return;
        Player player = sender instanceof Player p ? p : null;
        sender.sendMessage(parse(player, message));
    }

    public static String escapeTags(String input) {
        if (input == null || input.isEmpty()) return input;
        return MINI_MESSAGE.escapeTags(input);
    }

    /**
     * {@code %img_<tag>%} placeholder for the server's primary (highest-value) coin
     * denomination, resolved via {@link ItemsAdderFontHook} — drop this right next to a money
     * amount in any currency-related message (price, balance, "insufficient funds", …) to show
     * a coin glyph, matching LoveClans' established currency-icon convention. Empty string (no
     * visible artifact) when LoveCore's economy isn't up yet or has no denominations configured;
     * callers don't need to null-check.
     */
    public static String currencyIcon() {
        LoveShops plugin = LoveShops.getInstance();
        if (plugin == null) return "";
        return plugin.getEconomy().map(economy -> {
            List<Denomination> denominations = economy.denominations();
            if (denominations == null || denominations.isEmpty()) return "";
            String itemId = denominations.get(0).itemId();
            int colon = itemId.indexOf(':');
            String tag = colon >= 0 ? itemId.substring(colon + 1) : itemId;
            return "%img_" + tag + "% ";
        }).orElse("");
    }
}
