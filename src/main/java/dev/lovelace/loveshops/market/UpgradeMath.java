package dev.lovelace.loveshops.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cost of raising a trader from {@code level} to {@code level + 1}. */
public final class UpgradeMath {

    private UpgradeMath() {}

    /** {@code base * multiplier^(level-1)}, rounded, never below {@code base}, never overflowing. */
    public static long cost(int level, long base, double multiplier) {
        if (base <= 0) return 0;
        double m = Double.isNaN(multiplier) || multiplier < 1.0 ? 1.0 : multiplier;
        BigDecimal value = BigDecimal.valueOf(base);
        BigDecimal factor = BigDecimal.valueOf(m);
        for (int i = 1; i < Math.max(1, level); i++) {
            value = value.multiply(factor);
            if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE / 2)) > 0) return Long.MAX_VALUE / 2;
        }
        return Math.max(base, value.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    /** Shelf count at a level: the base plus one per level above the first. */
    public static int slots(int baseSlots, int level) {
        return baseSlots + Math.max(0, level - 1);
    }
}
