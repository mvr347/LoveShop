package dev.lovelace.loveshops.utils;

/**
 * Утилита для построения визуальных прогресс-баров в GUI и lore предметов.
 */
public final class ProgressBarUtil {

    private ProgressBarUtil() {}

    /**
     * Стандартный прогресс-бар:
     * {@code §8▰▰▰▰▰▰▰▰▱▱▱▱▱▱▱▱▱▱▱▱ §7(12/30 стаков)}
     */
    public static String formatProgressBar(long current, long max, int bars, String unitLabel) {
        if (max <= 0) max = 1;
        double ratio = Math.max(0.0, Math.min(1.0, (double) current / max));
        int filled = (int) Math.round(ratio * bars);

        StringBuilder sb = new StringBuilder("<dark_gray>");
        for (int i = 0; i < bars; i++) {
            if (i < filled) {
                sb.append("<green>▰</green>");
            } else {
                sb.append("<gray>▱</gray>");
            }
        }
        sb.append("</dark_gray> <gray>(").append(current).append("/").append(max)
                .append(unitLabel != null && !unitLabel.isBlank() ? " " + unitLabel : "")
                .append(")</gray>");
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
