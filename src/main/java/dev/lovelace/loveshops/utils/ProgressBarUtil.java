package dev.lovelace.loveshops.utils;

import java.util.List;

/**
 * Визуальные прогресс-бары для lore предметов.
 * Бар — отдельная строка в скобках, заполненная часть зелёная; подпись с числами идёт следующей строкой.
 */
public final class ProgressBarUtil {

    private ProgressBarUtil() {}

    /** {@code [■■■■■■■■■■■■■■■■■■■■]} — filled part green, the rest dark gray. */
    public static String bar(long current, long max, int bars) {
        int b = bars > 0 ? bars : 20;
        int filled = (int) Math.round(ratio(current, max) * b);
        StringBuilder sb = new StringBuilder("<dark_gray>[</dark_gray>");
        if (filled > 0) sb.append("<green>").append("■".repeat(filled)).append("</green>");
        if (filled < b) sb.append("<dark_gray>").append("■".repeat(b - filled)).append("</dark_gray>");
        return sb.append("<dark_gray>]</dark_gray>").toString();
    }

    /** {@code Заполненность 15 / 30 стаков (50%)}. */
    public static String caption(long current, long max, String unitLabel) {
        long m = max <= 0 ? 1 : max;
        int percent = (int) Math.round(ratio(current, m) * 100);
        return "<gray>Заполненность: </gray><yellow>" + current + "</yellow><gray> / " + m
                + (unitLabel != null && !unitLabel.isBlank() ? " " + unitLabel : "")
                + " (" + percent + "%)</gray>";
    }

    /** Two lore lines: the bar, then the caption below it. */
    public static List<String> formatProgressBar(long current, long max, int bars, String unitLabel) {
        return List.of(bar(current, max, bars), caption(current, max, unitLabel));
    }

    /** Progress in stacks (units / stackSize). */
    public static List<String> formatStackProgressBar(long currentUnits, long maxUnits, int stackSize, int bars) {
        int sz = Math.max(1, stackSize);
        long currentStacks = currentUnits / sz;
        long maxStacks = Math.max(1, maxUnits / sz);
        return formatProgressBar(currentStacks, maxStacks, bars, "стаков");
    }

    private static double ratio(long current, long max) {
        long m = max <= 0 ? 1 : max;
        return Math.max(0.0, Math.min(1.0, (double) current / m));
    }
}
