package dev.lovelace.loveshops.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Time window and category rotation of the daily buyer. Pure, hence unit-tested. */
public final class DailyBuyerMath {

    private DailyBuyerMath() {}

    /**
     * Is {@code minuteOfDay} (0..1439) inside the window that starts at {@code startHour} and lasts
     * {@code durationHours}? The window may run past midnight.
     */
    public static boolean isActive(int minuteOfDay, int startHour, int durationHours) {
        int duration = Math.max(0, Math.min(24, durationHours)) * 60;
        if (duration <= 0) return false;
        if (duration >= 24 * 60) return true;
        int start = Math.floorMod(startHour, 24) * 60;
        int since = Math.floorMod(minuteOfDay - start, 24 * 60);
        return since < duration;
    }

    /**
     * Today's categories: the same day always gives the same picks (every server restart and every
     * player sees one list), a new day a different one.
     */
    public static List<String> pickCategories(String dayKey, List<String> names, int count) {
        if (names == null || names.isEmpty() || count <= 0) return List.of();
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        Collections.shuffle(sorted, new Random(dayKey.hashCode() * 31L + sorted.size()));
        return List.copyOf(sorted.subList(0, Math.min(count, sorted.size())));
    }

    /** How many more items the player may sell at the bonus price today. */
    public static int remainingAllowance(int limitPerPlayer, int usedToday) {
        return Math.max(0, limitPerPlayer - Math.max(0, usedToday));
    }
}
