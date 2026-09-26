package dev.lovelace.loveshops.utils;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves {@code %img_<tag>%} / {@code %ia_<tag>%} placeholders in a message into an
 * ItemsAdder custom-font glyph. ItemsAdder registers these directly with PlaceholderAPI (its own
 * "Font image" PAPI placeholders section documents {@code %img_<name>%} as the literal syntax,
 * e.g. {@code %img_smile%}) — resolving through PAPI first is the correct, documented path.
 * The manual {@code %img_x%} -> {@code :x:} + {@code FontImages.replacePlaceholders} rewrite
 * below only runs on whatever PAPI didn't catch (e.g. PlaceholderAPI not installed), as a
 * fallback rather than the primary mechanism — same convention LoveClans ({@code
 * ItemsAdderFontHook}) and LoveBrew ({@code ItemsAdderHook#replaceFontImages}) already use for
 * their coin icons, ported here rather than reinventing a second one. Reflection-only for the
 * ItemsAdder half: no compile-time dependency on ItemsAdder, no-op (returns the text unchanged)
 * when neither plugin is installed.
 */
public final class ItemsAdderFontHook {

    private static final Logger LOGGER = Logger.getLogger("LoveShops");

    private ItemsAdderFontHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    public static String resolve(Player player, String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // Wrapped in <white>...</white> (MiniMessage tag, applied here since this runs before
        // MiniMessage deserialization - see MessageUtils.parse) BEFORE handing off to
        // PlaceholderAPI below, not after: ItemsAdder registers %img_x%/%ia_x% as its own PAPI
        // expansion, so on a server with both plugins installed (the normal production case)
        // PlaceholderAPI.setPlaceholders resolves the placeholder straight to the raw glyph
        // character, and by then there is no %img_x% token left to wrap - the glyph would
        // silently inherit whatever color tag is active around it (a red warning, a gold price
        // prefix, ...). Wrapping the literal token first survives both that PAPI path and the
        // manual FontImages fallback below, since PAPI and FontImages both do a plain
        // string-replace and don't care what surrounds the token they're replacing.
        if (text.contains("%img_") || text.contains("%ia_")) {
            text = text.replaceAll("%img_([a-zA-Z0-9_:]+)%", "<white>%img_$1%</white>")
                    .replaceAll("%ia_([a-zA-Z0-9_:]+)%", "<white>%ia_$1%</white>");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                text = PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable papiFailure) {
                LOGGER.log(Level.FINEST, "PlaceholderAPI resolution failed: " + papiFailure.getMessage());
            }
        }

        if (!isAvailable() || (!text.contains("%img_") && !text.contains("%ia_"))) {
            return text;
        }

        String withTags = text.replaceAll("%img_([a-zA-Z0-9_:]+)%", ":$1:")
                .replaceAll("%ia_([a-zA-Z0-9_:]+)%", ":$1:");

        try {
            Class<?> fontImagesClass = Class.forName("dev.lone.itemsadder.api.FontImages");
            try {
                Object result = fontImagesClass.getMethod("replacePlaceholders", Player.class, String.class)
                        .invoke(null, player, withTags);
                return result instanceof String resolved ? resolved : withTags;
            } catch (NoSuchMethodException noPlayerOverload) {
                Object result = fontImagesClass.getMethod("replacePlaceholders", String.class)
                        .invoke(null, withTags);
                return result instanceof String resolved ? resolved : withTags;
            }
        } catch (ReflectiveOperationException exception) {
            LOGGER.log(Level.FINEST, "ItemsAdder FontImages resolution failed: " + exception.getMessage());
            return withTags;
        }
    }
}
