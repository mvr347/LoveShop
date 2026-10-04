package dev.lovelace.loveshops.utils;

import dev.lovelace.lovecore.api.economy.Denomination;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Единый стандарт форматирования денег в виде глифов монет ItemsAdder.
 * Соответствует эталону BankerGui: разбиение суммы long по LoveEconomy.denominations()
 * от старшего к младшему с отображением %img_<tag>% и белым счётчиком (эквивалент &f xN).
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
        // 2026-10-03: by id only. The old value thresholds (>= 1000 netherite, >= 100 diamond, ...) labelled every coin
        // one tier too high once the denominations became 1/100/2000/20000.
        String id = den.itemId() != null ? den.itemId().toLowerCase() : "";
        if (id.contains("netherite")) return "<gradient:#9B51E0:#BB6BD9>Незеритовая монета</gradient>";
        if (id.contains("diamond")) return "<gradient:#00C9FF:#92FE9D>Алмазная монета</gradient>";
        if (id.contains("gold")) return "<gradient:#FFE000:#799F0C>Золотая монета</gradient>";
        if (id.contains("iron")) return "<gradient:#E0E0E0:#F2F2F2>Железная монета</gradient>";
        if (id.contains("copper")) return "<gradient:#E67E22:#D35400>Медная монета</gradient>";
        return "Монета";
    }

    public static String formatGlyphs(LoveEconomy eco, long amount) {
        return formatGlyphs(eco, amount, "&f");
    }

    /** Like {@link #formatGlyphs(LoveEconomy, long)}, but the counts are gray: for a solid-gray line of lore. */
    public static String formatGlyphsGray(LoveEconomy eco, long amount) {
        return formatGlyphs(eco, amount, "&7");
    }

    private static String formatGlyphs(LoveEconomy eco, long amount, String countColor) {
        if (eco == null) return "%img_copper_coin% " + countColor + " x0";
        List<Denomination> dens = new ArrayList<>(eco.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());

        if (amount <= 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            return (smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " " + countColor + " x0";
        }

        StringBuilder sb = new StringBuilder();
        long remaining = amount;
        for (Denomination den : dens) {
            if (den.value() <= 0) continue;
            long count = remaining / den.value();
            if (count > 0) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(getCoinGlyph(den)).append(" " + countColor + " x").append(count);
                remaining %= den.value();
            }
        }

        if (sb.length() == 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            return (smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " " + countColor + " x0";
        }

        return sb.toString();
    }

    public static String formatGlyphs(long amount) {
        LoveShops plugin = LoveShops.getInstance();
        return formatGlyphs(plugin != null ? plugin.getEconomy().orElse(null) : null, amount);
    }

    public static List<Component> formatGlyphLines(long amount) {
        LoveShops plugin = LoveShops.getInstance();
        return formatGlyphLines(plugin != null ? plugin.getEconomy().orElse(null) : null, amount);
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
                lines.add(getCoinGlyph(den) + " &f x" + count);
                remaining %= den.value();
            }
        }
        if (lines.isEmpty()) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            lines.add((smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " &f x0");
        }
        return lines;
    }

    public static List<Component> formatGlyphLines(LoveEconomy eco, long amount) {
        List<Component> lines = new ArrayList<>();
        for (String line : glyphLineStrings(eco, amount)) {
            lines.add(MessageUtils.parse(line));
        }
        return lines;
    }
}
