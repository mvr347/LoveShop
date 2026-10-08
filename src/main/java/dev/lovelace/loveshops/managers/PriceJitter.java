package dev.lovelace.loveshops.managers;

/** Random spread of a crate's starting price; it does not look at what is inside the crate. */
public final class PriceJitter {

    private PriceJitter() {
    }

    /**
     * @param base    configured starting price
     * @param percent maximum deviation in percent (0 = fixed price)
     * @param roll    uniform in [0, 1): 0 gives the lowest price, just under 1 the highest
     */
    public static int apply(int base, int percent, double roll) {
        if (base <= 0 || percent <= 0) return base;
        int p = Math.min(percent, 90);
        double factor = 1.0 + ((roll * 2.0) - 1.0) * p / 100.0;
        return Math.max(1, (int) Math.round(base * factor));
    }
}
