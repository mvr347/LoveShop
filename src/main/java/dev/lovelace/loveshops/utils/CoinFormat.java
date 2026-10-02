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
 * от старшего к младшему с отображением %img_<tag>% x<count>.
 */
public final class CoinFormat {

    private CoinFormat() {}

    /**
     * Возвращает тег глифа монеты (эталон BankerGui.getCoinGlyph):
     * itemId -> tag после ":" -> "%img_" + tag + "%"
     * null -> "%img_copper_coin%"
     */
    public static String getCoinGlyph(Denomination den) {
        if (den == null || den.itemId() == null) return "%img_copper_coin%";
        String id = den.itemId();
        int colon = id.indexOf(':');
        String tag = colon >= 0 ? id.substring(colon + 1) : id;
        return "%img_" + tag + "%";
    }

    /**
     * Градиентное название монеты (эталон BankerGui.getCoinName).
     */
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

    /**
     * Форматирует сумму в строку глифов:
     * {@code %img_diamond_coin% x2  %img_gold_coin% x1  %img_iron_coin% x3}
     * Если сумма <= 0 — отображается младший номинал x0.
     */
    public static String formatGlyphs(LoveEconomy eco, long amount) {
        if (eco == null) return "%img_copper_coin% x0";
        List<Denomination> dens = new ArrayList<>(eco.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());

        if (amount <= 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            return (smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " x0";
        }

        StringBuilder sb = new StringBuilder();
        long remaining = amount;
        for (Denomination den : dens) {
            if (den.value() <= 0) continue;
            long count = remaining / den.value();
            if (count > 0) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(getCoinGlyph(den)).append(" x").append(count);
                remaining %= den.value();
            }
        }

        if (sb.length() == 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            return (smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " x0";
        }

        return sb.toString();
    }

    /**
     * Форматирует сумму в строку глифов, используя экземпляр LoveShops.
     */
    public static String formatGlyphs(long amount) {
        LoveShops plugin = LoveShops.getInstance();
        return formatGlyphs(plugin != null ? plugin.getEconomy().orElse(null) : null, amount);
    }

    /**
     * Форматирует сумму построчно в виде списка Component для lore предметов, используя экземпляр LoveShops.
     */
    public static List<Component> formatGlyphLines(long amount) {
        LoveShops plugin = LoveShops.getInstance();
        return formatGlyphLines(plugin != null ? plugin.getEconomy().orElse(null) : null, amount);
    }

    /**
     * Сумма построчно строками MiniMessage: {@code %img_gold_coin% <yellow>x3</yellow>}, по строке на номинал.
     * Для lore, которое строит GUI из текстов (в отличие от {@link #formatGlyphLines}, возвращающего Component).
     */
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
                lines.add(getCoinGlyph(den) + " <yellow>x" + count + "</yellow>");
                remaining %= den.value();
            }
        }
        if (lines.isEmpty()) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            lines.add((smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%") + " <yellow>x0</yellow>");
        }
        return lines;
    }

    /**
     * Форматирует сумму построчно в виде списка Component для lore предметов.
     */
    public static List<Component> formatGlyphLines(LoveEconomy eco, long amount) {
        List<Component> lines = new ArrayList<>();
        if (eco == null) {
            lines.add(MessageUtils.parse("%img_copper_coin% <yellow>x0</yellow>"));
            return lines;
        }

        List<Denomination> dens = new ArrayList<>(eco.denominations());
        dens.sort(Comparator.comparingLong(Denomination::value).reversed());

        if (amount <= 0) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            String glyph = smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%";
            lines.add(MessageUtils.parse(glyph + " <yellow>x0</yellow>"));
            return lines;
        }

        long remaining = amount;
        for (Denomination den : dens) {
            if (den.value() <= 0) continue;
            long count = remaining / den.value();
            if (count > 0) {
                String glyph = getCoinGlyph(den);
                lines.add(MessageUtils.parse(glyph + " <yellow>x" + count + "</yellow>"));
                remaining %= den.value();
            }
        }

        if (lines.isEmpty()) {
            Denomination smallest = dens.isEmpty() ? null : dens.get(dens.size() - 1);
            String glyph = smallest != null ? getCoinGlyph(smallest) : "%img_copper_coin%";
            lines.add(MessageUtils.parse(glyph + " <yellow>x0</yellow>"));
        }

        return lines;
    }
}

