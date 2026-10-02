package dev.lovelace.loveshops.market;

import java.util.Arrays;
import java.util.List;

/**
 * Which "rent ends soon" reminder is due, free of Bukkit so it can be tested. Thresholds are hours
 * before the end of the term (e.g. 72, 24, 6, 1). A reminder is sent once per threshold: when the
 * time left first drops to it. If several thresholds were crossed at once (the server was off) only
 * the most urgent one is sent. Renewing the rent (time left grows again) re-arms every threshold.
 */
public final class RentReminderMath {

    private RentReminderMath() {}

    /**
     * What to do with one point.
     *
     * @param send  the threshold (hours) to remind about now, or {@code null} for no reminder
     * @param reset {@code true} when the stored "last sent" threshold must be forgotten
     */
    public record Decision(Integer send, boolean reset) {}

    private static final Decision NOTHING = new Decision(null, false);

    /** Sorted, distinct, positive thresholds, biggest first; falls back to 72/24/6/1. */
    public static int[] normalize(List<Integer> configured) {
        int[] values = configured == null ? new int[0]
                : configured.stream().filter(v -> v != null && v > 0).mapToInt(Integer::intValue).distinct().toArray();
        if (values.length == 0) values = new int[]{72, 24, 6, 1};
        Arrays.sort(values);
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            int tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
        return values;
    }

    /**
     * @param thresholdsDesc  thresholds in hours, biggest first (see {@link #normalize})
     * @param millisLeft      time to the end of the term; {@code <= 0} means the term is over (grace period)
     * @param lastSentHours   the threshold reminded about last time, {@code null} if none yet
     */
    public static Decision decide(int[] thresholdsDesc, long millisLeft, Integer lastSentHours) {
        if (thresholdsDesc.length == 0) return NOTHING;
        long biggest = thresholdsDesc[0] * 3_600_000L;
        if (millisLeft > biggest) {
            return lastSentHours == null ? NOTHING : new Decision(null, true);
        }
        if (millisLeft <= 0) return NOTHING;
        // The renewal pushed the end back past the threshold we last reminded about: start over.
        if (lastSentHours != null && millisLeft > lastSentHours * 3_600_000L) {
            lastSentHours = null;
        }
        Integer due = null;
        for (int t : thresholdsDesc) {
            if (millisLeft <= t * 3_600_000L) due = t; // keeps shrinking to the most urgent crossed one
        }
        boolean reset = false;
        if (due != null && (lastSentHours == null || due < lastSentHours)) {
            return new Decision(due, reset);
        }
        return NOTHING;
    }
}
