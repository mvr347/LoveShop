package dev.lovelace.loveshops.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The arithmetic of robbing a stall. Pure, so every number a player can feel is unit-tested.
 * <ul>
 *   <li>A robber's own chance grows a little with every click of the day.</li>
 *   <li>Several robbers at one stall combine as {@code 1 - prod(1 - p_i)}: two get almost double,
 *       every further one adds less, and the total can never reach 100%.</li>
 *   <li>A "day" starts at a fixed hour, not at midnight, so late-evening play is one session.</li>
 * </ul>
 */
public final class RobberyMath {

    private RobberyMath() {}

    /**
     * Chance of the {@code k}-th click of the day (k starts at 1): {@code base + step * (k - 1)},
     * never above {@code cap}, never negative.
     */
    public static double chance(int k, double base, double step, double cap) {
        if (k < 1) return 0.0;
        double p = base + step * (k - 1);
        return Math.max(0.0, Math.min(cap, p));
    }

    /**
     * Probability that at least one of independent attempts succeeds, capped at {@code maxCombined}
     * so no crowd of robbers ever makes it certain.
     */
    public static double combined(List<Double> chances, double maxCombined) {
        double miss = 1.0;
        for (Double c : chances) {
            if (c == null) continue;
            double p = Math.max(0.0, Math.min(1.0, c));
            miss *= (1.0 - p);
        }
        double result = 1.0 - miss;
        return Math.min(Math.max(0.0, maxCombined), result);
    }

    /** Chance that a lone robber succeeds at least once over {@code attempts} clicks of one day. */
    public static double dailyChance(int attempts, double base, double step, double cap) {
        double miss = 1.0;
        for (int k = 1; k <= attempts; k++) miss *= (1.0 - chance(k, base, step, cap));
        return 1.0 - miss;
    }

    /** Key of the "day" {@code nowMillis} belongs to; the day rolls over at {@code resetHour} local time. */
    public static String dayKey(long nowMillis, int resetHour, ZoneId zone) {
        int hour = Math.max(0, Math.min(23, resetHour));
        LocalDate date = Instant.ofEpochMilli(nowMillis).atZone(zone).minusHours(hour).toLocalDate();
        return date.toString();
    }

    /**
     * How patient the NPC still is, 1.0 (calm) ... 0.0 (out of patience), after {@code clicks} of
     * {@code budget} clicks.
     */
    public static double patience(int clicks, int budget) {
        if (budget <= 0) return 0.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - (double) clicks / budget));
    }

    /** Speech stage for a patience value: 0 calm, 1 annoyed, 2 warning. */
    public static int stage(double patience) {
        if (patience > 0.66) return 0;
        if (patience > 0.33) return 1;
        return 2;
    }

    /** How much of {@code total} a robber takes: {@code percent} of it, at least 1 when there is anything. */
    public static long coinLoot(long total, double percent) {
        if (total <= 0) return 0;
        long loot = (long) Math.floor(total * Math.max(0.0, Math.min(100.0, percent)) / 100.0);
        return Math.min(total, Math.max(1L, loot));
    }

    /** How many shelves a robber empties: {@code percent} of the shelves in stock, at least one, at most {@code maxItems}. */
    public static int itemShelvesToTake(int shelvesInStock, double percent, int maxItems) {
        if (shelvesInStock <= 0 || maxItems <= 0) return 0;
        int byPercent = (int) Math.ceil(shelvesInStock * Math.max(0.0, Math.min(100.0, percent)) / 100.0);
        return Math.min(Math.min(shelvesInStock, maxItems), Math.max(1, byPercent));
    }

    /** Millisecond timestamp at which the "day" named {@code dayKey} started. */
    public static long dayStartMillis(String dayKey, int resetHour, ZoneId zone) {
        int hour = Math.max(0, Math.min(23, resetHour));
        return LocalDate.parse(dayKey).atStartOfDay(zone).plusHours(hour).toInstant().toEpochMilli();
    }
}
