package dev.lovelace.loveshops.utils;

import java.util.List;

/**
 * Визуальные прогресс-бары для lore предметов по стандарту gui-gen-6 (16 ячеек █).
 */
public final class ProgressBarUtil {

    public static final int DEFAULT_CELLS = 16;

    private ProgressBarUtil() {}

    /** 16-cell progress bar in gui-gen-6 standard: {@code &8[&6███████████&8█████&8] &8(&669%&8)}. */
    public static String bar(long current, long max) {
        return bar(current, max, DEFAULT_CELLS);
    }

    public static String bar(long current, long max, int cells) {
        int c = cells > 0 ? cells : DEFAULT_CELLS;
        int percent = percent(current, max);
        int filled = (int) Math.round(c * (percent / 100.0));
        filled = Math.max(0, Math.min(c, filled));
        String fillColor = percent >= 100 ? "green" : "gold";
        return "<dark_gray>[</dark_gray><" + fillColor + ">" + "█".repeat(filled) + "</" + fillColor + ">"
                + "<dark_gray>" + "█".repeat(c - filled) + "]</dark_gray> "
                + "<dark_gray>(<" + fillColor + ">" + percent + "%</" + fillColor + "><dark_gray>)";
    }

    public static String bar(long current, long max, String fillColor) {
        int percent = percent(current, max);
        int filled = (int) Math.round(DEFAULT_CELLS * (percent / 100.0));
        filled = Math.max(0, Math.min(DEFAULT_CELLS, filled));
        return "<dark_gray>[<" + fillColor + ">" + "█".repeat(filled) + "</" + fillColor + ">"
                + "<dark_gray>" + "█".repeat(DEFAULT_CELLS - filled) + "]</dark_gray> "
                + "<dark_gray>(<" + fillColor + ">" + percent + "%</" + fillColor + "><dark_gray>)";
    }

    public static int percent(long current, long max) {
        if (max <= 0 || current <= 0) return 0;
        return (int) Math.min(100, current * 100 / max);
    }

    /** Подпись к прогресс-бару. */
    public static String caption(long current, long max, String unitLabel) {
        long m = max <= 0 ? 1 : max;
        int percent = percent(current, m);
        return "<gray>Заполненность: </gray><yellow>" + current + "</yellow><gray> / " + m
                + (unitLabel != null && !unitLabel.isBlank() ? " " + unitLabel : "")
                + " (" + percent + "%)</gray>";
    }

    public static List<String> formatProgressBar(long current, long max, int bars, String unitLabel) {
        return List.of(bar(current, max, bars), caption(current, max, unitLabel));
    }

    public static List<String> formatStackProgressBar(long currentUnits, long maxUnits, int stackSize, int bars) {
        int sz = Math.max(1, stackSize);
        long currentStacks = currentUnits / sz;
        long maxStacks = Math.max(1, maxUnits / sz);
        return formatProgressBar(currentStacks, maxStacks, bars, "стаков");
    }
}
