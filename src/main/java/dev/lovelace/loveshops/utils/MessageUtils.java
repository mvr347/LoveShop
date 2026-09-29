package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.loveshops.LoveShops;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class MessageUtils {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

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
        // Legacy codes (&a, §c, &#rrggbb) and MiniMessage tags may be mixed in one string; deserializing
        // the string as only one of the two used to print the other kind literally.
        return MINI_MESSAGE.deserialize(legacyToMiniMessage(resolved));
    }

    /** Rewrites {@code &x}/{@code §x} colour/format codes and {@code &#rrggbb} into MiniMessage tags. */
    static String legacyToMiniMessage(String input) {
        if (input.indexOf('&') < 0 && input.indexOf('§') < 0) return input;
        StringBuilder out = new StringBuilder(input.length() + 16);
        int n = input.length();
        for (int i = 0; i < n; i++) {
            char c = input.charAt(i);
            if ((c == '&' || c == '§') && i + 1 < n) {
                char next = input.charAt(i + 1);
                if (next == '#' && i + 7 < n && isHex(input, i + 2, 6)) {
                    out.append("<#").append(input, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                String tag = legacyTag(Character.toLowerCase(next));
                if (tag != null) {
                    out.append(tag);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int len) {
        for (int i = from; i < from + len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static String legacyTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
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
