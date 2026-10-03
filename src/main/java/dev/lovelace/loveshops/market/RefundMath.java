package dev.lovelace.loveshops.market;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * What a tenant gets back for the rent they have not used when they hand the point back to the
 * landlord: {@code percent} of the price of the time still left, rounded down.
 */
public final class RefundMath {

    private RefundMath() {}

    /**
     * @param periodPrice     price of one rental period
     * @param remainingMillis time left of the rent (the grace period is not rent, so pass 0 there)
     * @param periodMillis    length of one rental period
     * @param percent         share given back, clamped to 0..100
     */
    public static long refund(long periodPrice, long remainingMillis, long periodMillis, int percent) {
        if (periodPrice <= 0 || remainingMillis <= 0 || periodMillis <= 0 || percent <= 0) return 0L;
        int share = Math.min(100, percent);
        BigInteger value = BigInteger.valueOf(periodPrice)
                .multiply(BigInteger.valueOf(remainingMillis))
                .multiply(BigInteger.valueOf(share));
        BigInteger divisor = BigInteger.valueOf(periodMillis).multiply(BigInteger.valueOf(100));
        BigInteger result = new BigDecimal(value).divide(new BigDecimal(divisor), 0, RoundingMode.DOWN).toBigInteger();
        return result.bitLength() > 62 ? Long.MAX_VALUE / 2 : result.longValue();
    }
}
