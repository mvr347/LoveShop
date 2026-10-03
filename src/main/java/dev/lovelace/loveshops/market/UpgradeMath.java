package dev.lovelace.loveshops.market;

/** Cost of raising a trader from {@code level} to {@code level + 1}. */
public final class UpgradeMath {

    private UpgradeMath() {}

    /**
     * Price of raising a point from {@code level} to {@code level + 1}: {@code baseCoins} of the
     * biggest coin for the first step, {@code stepCoins} more for every further one (1, 2, 3 ... coins).
     */
    public static long linearCost(int level, long coinValue, long baseCoins, long stepCoins) {
        if (coinValue <= 0 || baseCoins <= 0) return 0;
        long coins = baseCoins + Math.max(0, level - 1) * Math.max(0L, stepCoins);
        if (coins > Long.MAX_VALUE / coinValue) return Long.MAX_VALUE / 2;
        return coins * coinValue;
    }

    /** The level at which the point has {@code maxShelves} shelves (the biggest menu), at least 1. */
    public static int maxLevel(int maxShelves, int baseSlots) {
        return Math.max(1, maxShelves - Math.max(1, baseSlots) + 1);
    }

    /** Shelf count at a level: the base plus one per level above the first. */
    public static int slots(int baseSlots, int level) {
        return baseSlots + Math.max(0, level - 1);
    }

    /** Storage capacity (in stacks) at a level: base plus storage-per-level for each level above the first. */
    public static int storageCapacity(int baseStorage, int storagePerLevel, int level) {
        return Math.max(1, baseStorage + Math.max(0, level - 1) * Math.max(0, storagePerLevel));
    }
}
