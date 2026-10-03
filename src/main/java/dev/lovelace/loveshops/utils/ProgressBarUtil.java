package dev.lovelace.loveshops.utils;

/**
 * Утилита для построения визуальных прогресс-баров в GUI и lore предметов.
 */
public final class ProgressBarUtil {

    private ProgressBarUtil() {}

    /**
     * Стандартный прогресс-бар:
     * {@code &f■■■■■■■■■■&8■■■■■■■■■■ &fЗаполненность &e15 &f/ 30 стаков &7(50%)}
     */
    public static String formatProgressBar(long current, long max, int bars, String unitLabel) {
        if (max <= 0) max = 1;
        double ratio = Math.max(0.0, Math.min(1.0, (double) current / max));
        int b = bars > 0 ? bars : 20;
        int filled = (int) Math.round(ratio * b);
        int percent = (int) Math.round(ratio * 100);

        StringBuilder sb = new StringBuilder("&f");
        for (int i = 0; i < filled; i++) {
            sb.append("■");
        }
        sb.append("&8");
        for (int i = filled; i < b; i++) {
            sb.append("■");
        }
        sb.append(" &f Заполненность &e").append(current).append(" &f / ").append(max)
                .append(unitLabel != null && !unitLabel.isBlank() ? " " + unitLabel : "")
                .append(" &7(").append(percent).append("%)");
        return sb.toString();
    }

    /**
     * Прогресс-бар в стаках (при maxUnits и currentUnits):
     */
    public static String formatStackProgressBar(long currentUnits, long maxUnits, int stackSize, int bars) {
        int sz = Math.max(1, stackSize);
        long currentStacks = currentUnits / sz;
        long maxStacks = Math.max(1, maxUnits / sz);
        return formatProgressBar(currentStacks, maxStacks, bars, "стаков");
    }
}
