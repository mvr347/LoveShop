package dev.lovelace.loveshops.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves {@code %img_<tag>%} / {@code %ia_<tag>%} placeholders in a message into an
 * ItemsAdder custom-font glyph (the negative-space-font icon technique ItemsAdder's FontImages
 * API implements) — the same convention LoveClans ({@code ItemsAdderFontHook}), LoveBrew
 * ({@code PhysicalCurrencyManager}/{@code ItemsAdderHook}) and LoveTweaks
 * ({@code CurrencyFormatter}) already use for their coin icons, ported here rather than
 * reinventing a second one. Reflection-only: no compile-time dependency on ItemsAdder, no-op
 * (returns the text unchanged) when the plugin isn't installed.
 */
public final class ItemsAdderFontHook {

    private static final Logger LOGGER = Logger.getLogger("LoveShops");

    private ItemsAdderFontHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }

    public static String resolve(Player player, String text) {
        if (text == null || text.isBlank() || !isAvailable()) {
            return text;
        }
        if (!text.contains("%img_") && !text.contains("%ia_")) {
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
