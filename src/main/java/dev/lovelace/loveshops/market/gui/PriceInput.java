package dev.lovelace.loveshops.market.gui;

/**
 * State of the price picker, free of Bukkit so it can be tested: a price built from coin
 * denominations. Shift cycles the active coin, left click adds one of it, right click takes one
 * away (the LoveDuels stake picker works the same way). The price starts at 0 = "not set yet",
 * and only a price inside {@code [min, max]} can be confirmed.
 */
public final class PriceInput {

    private final long[] units;
    private final long min;
    private final long max;
    private int active;
    private long price;

    /**
     * @param units denomination values, smallest first; empty means a plain step of 1
     * @param start the price to start from (0 = not set)
     */
    public PriceInput(long[] units, long min, long max, long start) {
        this.units = units.length == 0 ? new long[]{1L} : units.clone();
        this.min = Math.max(1L, min);
        this.max = Math.max(this.min, max);
        this.price = Math.max(0L, Math.min(this.max, start));
    }

    public long price() { return price; }

    public long min() { return min; }

    public long max() { return max; }

    public int activeIndex() { return active; }

    public long activeUnit() { return units[active]; }

    public int unitCount() { return units.length; }

    public long unit(int index) { return units[index]; }

    /** Next coin; wraps around after the last one. */
    public void cycle() {
        active = (active + 1) % units.length;
    }

    /** One more of the active coin, never above the maximum. */
    public void add() {
        price = Math.min(max, price + units[active]);
    }

    /** One less of the active coin, never below zero ("not set"). */
    public void subtract() {
        price = Math.max(0L, price - units[active]);
    }

    /** {@code true} when the price may be confirmed. */
    public boolean valid() {
        return price >= min && price <= max;
    }
}
