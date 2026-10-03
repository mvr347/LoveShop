package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Единый стиль отображения монет LoveEconomy в GUI/сообщениях:
 * глиф %img_&lt;tag&gt;% и белый счётчик &lt;white&gt;xN&lt;/white&gt;.
 */
public final class CoinFormat {

    private CoinFormat() {}

    public static String getCoinGlyph(Denomination den) {
        if (den == null || den.itemId() == null) return "%img_copper_coin%";
        String id = den.itemId();
        int colon = id.indexOf(':');
        String tag = colon >= 0 ? id.substring(colon + 1) : id;
        return "%img_" + tag + "%";
    }

    public static String getCoinName(Denomination den) {
        if (den == null) return "Монета";
        String id = den.itemId() != null ? den.itemId().toLowerCase() : "";
        long val = den.value();
        if (id.contains("netherite") || val >= 1000) return "<gradient:#9B51E0:#BB6BD9>Незеритовая монета</gradient>";
        if (id.contains("diamond") || val >= 100) return "<gradient:#00C9FF:#92FE9D>Алмазная монета</gradient>";
        if (id.contains("gold") || val >= 50) return "<gradient:#FFE000:#799F0C>Золотая монета</gradient>";
        if (id.contains("iron") || val >= 10) return "<gradient:#E0E0E0:#F2F2F2>Железная монета</gradient>";
        return "<gradient:#E67E22:#D35400>Медная монета</gradient>";
    }

    public static String formatGlyphs(LoveEconomy eco, long amount) {
        if (eco == null) return "%img_copper_coin% <white>x0</white>";
        List<Denomination> dens = new ArrayList<>(eco.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());

        if (amount <= 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            return (smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " <white>x0</white>";
        }

        StringBuilder sb = new StringBuilder();
        long remaining = amount;
        for (Denomination den : dens) {
            if (den.value() <= 0) continue;
            long count = remaining / den.value();
            if (count > 0) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(getCoinGlyph(den)).append(" <white>x").append(count).append("</white>");
                remaining %= den.value();
            }
        }

        if (sb.length() == 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            return (smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " <white>x0</white>";
        }
        return sb.toString();
    }

    public static String formatGlyphs(LoveShops plugin, long amount) {
        return formatGlyphs(plugin != null ? plugin.getEconomy().orElse(null) : null, amount);
    }

    public static List<String> glyphLineStrings(LoveEconomy eco, long amount) {
        List<String> lines = new ArrayList<>();
        List<Denomination> dens = eco == null ? List.of() : new ArrayList<>(eco.denominations());
        dens = new ArrayList<>(dens);
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());
        long remaining = Math.max(0L, amount);
        for (Denomination den : dens) {
            if (den.value() <= 0) continue;
            long count = remaining / den.value();
            if (count > 0) {
                lines.add(getCoinGlyph(den) + " <white>x" + count + "</white>");
                remaining %= den.value();
            }
        }
        if (lines.isEmpty()) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            lines.add((smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " <white>x0</white>");
        }
        return lines;
    }

    public static List<Component> formatGlyphLines(LoveEconomy eco, long amount) {
        List<Component> lines = new ArrayList<>();
        if (eco == null) {
            lines.add(MessageUtils.parse("%img_copper_coin% <white>x0</white>"));
            return lines;
        }

        List<Denomination> dens = new ArrayList<>(eco.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());

        if (amount <= 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            String glyph = smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%";
            lines.add(MessageUtils.parse(glyph + " <white>x0</white>"));
            return lines;
        }

        long remaining = amount;
        for (Denomination den : dens) {
            if (den.value() <= 0) continue;
            long count = remaining / den.value();
            if (count > 0) {
                String glyph = getCoinGlyph(den);
                lines.add(MessageUtils.parse(glyph + " <white>x" + count + "</white>"));
                remaining %= den.value();
            }
        }

        if (lines.isEmpty()) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            String glyph = smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%";
            lines.add(MessageUtils.parse(glyph + " <white>x0</white>"));
        }

        return lines;
    }
}
