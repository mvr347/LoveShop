package dev.lovelace.loveshops.market;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Tax arithmetic in exact decimals: sums are up to ~10^17, where a double loses whole coins. */
public final class TaxMath {

    /** {@code tax + net == total}, always. */
    public record Split(long tax, long net) {}

    private TaxMath() {}

    /** @param rate 0.0 .. 1.0 (values outside are clamped) */
    public static Split split(long total, double rate) {
        if (total <= 0) return new Split(0, Math.max(0, total));
        double r = Double.isNaN(rate) ? 0.0 : Math.min(1.0, Math.max(0.0, rate));
        long tax = BigDecimal.valueOf(total).multiply(BigDecimal.valueOf(r))
                .setScale(0, RoundingMode.HALF_UP).longValue();
        tax = Math.min(total, Math.max(0, tax));
        return new Split(tax, total - tax);
    }
}
