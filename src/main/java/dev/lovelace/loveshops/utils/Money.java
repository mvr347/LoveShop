package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.lovecore.api.economy.MoneyConfig;
import dev.lovelace.lovecore.api.economy.MoneyParser;
import dev.lovelace.lovecore.api.economy.PriceOracle;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;
import java.util.OptionalLong;

/**
 * Money values of the plugin configs: a number (copper units) or a string such as {@code "3i 50c"}
 * (c / i / g / d = copper / iron / gold / diamond coin), see LoveCore {@link MoneyParser}. Also the single
 * place that talks to LoveCore's price model, so a missing or not-yet-ready model is handled in one spot.
 */
public final class Money {

    private Money() {
    }

    /** Config money value with the LoveCore price index applied; a malformed value logs a warning and gives {@code def}. */
    public static long scaled(ConfigurationSection section, String path, long def) {
        if (section == null) return def;
        try {
            return MoneyConfig.getScaled(section, path, def);
        } catch (Throwable t) {
            return plain(section, path, def); // LoveCore API missing
        }
    }

    /** Config money value without the price index (limits, caps, thresholds that must not inflate). */
    public static long raw(ConfigurationSection section, String path, long def) {
        if (section == null) return def;
        try {
            return MoneyConfig.get(section, path, def);
        } catch (Throwable t) {
            return plain(section, path, def);
        }
    }

    private static long plain(ConfigurationSection section, String path, long def) {
        Object value = section.get(path);
        return value instanceof Number n ? n.longValue() : def;
    }

    /** A typed amount ("1500", "3i 50c"). @throws IllegalArgumentException when it cannot be read. */
    public static long parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ignored) {
            // not a plain number: try the denomination syntax
        }
        List<dev.lovelace.lovecore.api.economy.Denomination> dens = LoveCore.service(LoveEconomy.class)
                .map(LoveEconomy::allDenominations).orElse(null);
        return MoneyParser.parse(trimmed, dens);
    }

    /** Materials already reported as "no model price", so the log is not flooded on every lookup. */
    private static final java.util.Set<Material> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile boolean warnedNotReady;

    /**
     * Value of one item from LoveCore's recipe-based price model; empty while the model is not ready or does not know it.
     * The fallback to flat config prices used to be silent, so a stale price could hide for weeks: it is logged once now.
     */
    public static OptionalLong modelValue(Material material) {
        if (material == null) return OptionalLong.empty();
        try {
            var oracle = LoveCore.service(PriceOracle.class);
            if (oracle.isPresent() && oracle.get().ready()) {
                OptionalLong value = oracle.get().value(material);
                if (value.isPresent() && value.getAsLong() > 0) return value;
                if (WARNED.add(material)) {
                    log("LoveCore price model has no price for " + material.name()
                            + ": the flat price from config.yml is used (set one with /lovecoreadmin economy setprice).");
                }
            } else if (!warnedNotReady) {
                warnedNotReady = true;
                log("LoveCore price model is not ready: flat prices from config.yml are used until it is built.");
            }
        } catch (Throwable t) {
            // LoveCore price API unavailable
        }
        return OptionalLong.empty();
    }

    private static void log(String message) {
        var plugin = dev.lovelace.loveshops.LoveShops.getInstance();
        if (plugin != null) plugin.getLogger().warning(message);
    }

    /**
     * Percent of a model value, at least 1 for a priced item; used for payouts (buyer) and mark-ups (merchants).
     * 0 when there is no value or the percent is not a positive number (a misconfigured percent must not pay 1 for everything).
     */
    public static long percentOf(long value, double percent) {
        if (value <= 0 || !Double.isFinite(percent) || percent <= 0) return 0L;
        return Math.max(1L, Math.round(value * percent / 100.0));
    }
}
