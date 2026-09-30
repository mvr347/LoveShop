package dev.lovelace.loveshops.market;

import java.util.Locale;
import java.util.Map;

/**
 * Argument parsing of the market's administrator commands, free of Bukkit so every edge case
 * (overflow, signs, percent marks, Russian command words) can be tested.
 */
public final class AdminParse {

    /** A multiplier of a merchant's prices is limited so a typo cannot zero or explode the economy. */
    public static final double MULT_MIN = -90.0;
    public static final double MULT_MAX = 500.0;

    /** Longest lot an administrator may create, in hours (30 days). */
    public static final int MAX_LOT_HOURS = 720;

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("цена", "price"), Map.entry("цены", "price"),
            Map.entry("аукцион", "auction"), Map.entry("барахолка", "flea"),
            Map.entry("точка", "point"), Map.entry("точки", "point"),
            Map.entry("список", "list"), Map.entry("получить", "get"), Map.entry("сбросить", "reset"),
            Map.entry("множитель", "mult"), Map.entry("границы", "bounds"), Map.entry("история", "history"),
            Map.entry("создать", "create"), Map.entry("выкуп", "buyout"), Map.entry("продлить", "extend"),
            Map.entry("завершить", "end"), Map.entry("отменить", "cancel"), Map.entry("шаг", "step"),
            Map.entry("снять", "remove"), Map.entry("лимит", "limit"), Map.entry("бан", "ban"),
            Map.entry("разбан", "unban"), Map.entry("закрыть", "close"), Map.entry("открыть", "open"),
            Map.entry("изъять", "seize"), Map.entry("восстановить", "restore"), Map.entry("ограбления", "robberies"));

    private AdminParse() {
    }

    /** Lower-cases a command word and maps a Russian alias to the English one. */
    public static String canonical(String word) {
        if (word == null) return "";
        String lower = word.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(lower, lower);
    }

    /**
     * A whole number in {@code 1 .. max}; {@code null} for anything else, including text, zero,
     * negatives and values that do not fit a {@code long}.
     */
    public static Long amount(String raw, long max) {
        if (raw == null) return null;
        try {
            long v = Long.parseLong(raw.trim());
            return v >= 1 && v <= max ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Like {@link #amount} but 0 is allowed (means "not set"). */
    public static Long amountOrZero(String raw, long max) {
        if (raw == null) return null;
        try {
            long v = Long.parseLong(raw.trim());
            return v >= 0 && v <= max ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code +10}, {@code -15}, {@code 12.5}, {@code 10%} -> the number; {@code null} when not a finite number. */
    public static Double percent(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.endsWith("%")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("+")) s = s.substring(1);
        if (s.isEmpty()) return null;
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean multiplierInRange(double percent) {
        return percent >= MULT_MIN && percent <= MULT_MAX;
    }

    /** Bid step: {@code 5%} is a percentage of the standing bid, a bare {@code 50} a fixed amount. */
    public record Step(String type, int value) {}

    public static Step step(String raw, int maxFixed) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        boolean pct = s.endsWith("%");
        Long v = amount(pct ? s.substring(0, s.length() - 1) : s, pct ? 100 : maxFixed);
        return v == null ? null : new Step(pct ? "percentage" : "fixed", v.intValue());
    }

    /** {@code 3д 4ч}, {@code 5ч 12м}, {@code 8м}, {@code 40с}: the two largest non-zero units. */
    public static String duration(long seconds) {
        if (seconds <= 0) return "0с";
        long d = seconds / 86_400;
        long h = seconds % 86_400 / 3_600;
        long m = seconds % 3_600 / 60;
        long s = seconds % 60;
        if (d > 0) return h > 0 ? d + "д " + h + "ч" : d + "д";
        if (h > 0) return m > 0 ? h + "ч " + m + "м" : h + "ч";
        if (m > 0) return m + "м";
        return s + "с";
    }
}
