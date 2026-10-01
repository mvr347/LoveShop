package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.CoinFormat;

import java.util.Optional;

/**
 * One place for how the market looks: money as coin glyphs (exactly like the banker), plus the
 * few icons. Only glyphs that already exist in the resource pack are used by default (the coin
 * tags the banker uses); every other icon is a plain symbol unless the server owner maps it to
 * an ItemsAdder tag in {@code market.glyphs.<name>}.
 */
public final class MarketStyle {

    /** Logical icons of the market. */
    public enum Icon {
        STALL("❖"), GUARD("⚔"), ROBBERY("☠"), STAR("★"), CLOSED("✖"),
        /** The till is money: shown as the biggest coin glyph the banker already uses. */
        TILL("%img_gold_coin%");

        private final String fallback;

        Icon(String fallback) {
            this.fallback = fallback;
        }
    }

    private final LoveShops plugin;

    public MarketStyle(LoveShops plugin) {
        this.plugin = plugin;
    }

    public String icon(Icon icon) {
        String override = plugin.getMarketConfig().glyphOverride(icon.name());
        return override == null || override.isBlank() ? icon.fallback : override;
    }

    /**
     * Amount as coins: {@code %img_gold_coin% x2  %img_iron_coin% x5}.
     * Formatted strictly via {@link CoinFormat}.
     */
    public String money(long amount) {
        Optional<LoveEconomy> economy = plugin.getEconomy();
        return CoinFormat.formatGlyphs(economy.orElse(null), amount);
    }

    /** Stall title used by the GUIs and speech: icon + gradient name. */
    public String stallTitle(String ownerName) {
        String name = ownerName == null || ownerName.isBlank() ? "?" : ownerName;
        return icon(Icon.STALL) + " <gradient:#E67E22:#D35400>Торговая точка</gradient> <white>" + name.replace('<', ' ') + "</white>";
    }
}
