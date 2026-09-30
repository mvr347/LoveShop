package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.BankerGui;

import java.util.List;
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
        STALL("❖"), GUARD("⚔"), ROBBERY("☠"), STAR("★"), CLOSED("✖"), FLEA("✦"),
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
     * Amount as coins: {@code %img_gold_coin% <yellow>x2</yellow> %img_iron_coin% <yellow>x5</yellow>}.
     * Falls back to a plain number when the economy service is not up.
     */
    public String money(long amount) {
        Optional<LoveEconomy> economy = plugin.getEconomy();
        if (economy.isEmpty()) return "<yellow>" + Math.max(0L, amount) + "</yellow>";
        List<Denomination> dens = economy.get().denominations();
        List<MoneySplit.Part> parts = MoneySplit.split(amount, dens);
        if (parts.isEmpty()) {
            Denomination smallest = MoneySplit.smallest(dens);
            return smallest == null ? "<yellow>0</yellow>"
                    : BankerGui.getCoinGlyph(smallest) + " <yellow>x0</yellow>";
        }
        StringBuilder sb = new StringBuilder();
        for (MoneySplit.Part part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(BankerGui.getCoinGlyph(part.denomination())).append(" <yellow>x").append(part.count()).append("</yellow>");
        }
        return sb.toString();
    }

    /** Stall title used by the GUIs and speech: icon + gradient name. */
    public String stallTitle(String ownerName) {
        String name = ownerName == null || ownerName.isBlank() ? "?" : ownerName;
        return icon(Icon.STALL) + " <gradient:#E67E22:#D35400>Палатка</gradient> <white>" + name.replace('<', ' ') + "</white>";
    }
}
