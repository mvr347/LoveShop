package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefundMathTest {

    private static final long WEEK = 7L * 86_400_000L;

    @Test
    void halfOfAFullWeekAtFiftyPercent() {
        assertEquals(50, RefundMath.refund(100, WEEK, WEEK, 50));
    }

    @Test
    void proportionalToTheTimeLeft() {
        assertEquals(100, RefundMath.refund(100, 4 * WEEK, WEEK, 25));   // 4 weeks * 100 * 25%
    }

    @Test
    void roundsDown() {
        // a third of a week at 100 per week and 50%: 16.67 -> 16
        assertEquals(16, RefundMath.refund(100, WEEK / 3, WEEK, 50));
    }

    @Test
    void nothingForNoTimeNoPriceNoPercent() {
        assertEquals(0, RefundMath.refund(100, 0, WEEK, 50));
        assertEquals(0, RefundMath.refund(100, -5, WEEK, 50));
        assertEquals(0, RefundMath.refund(0, WEEK, WEEK, 50));
        assertEquals(0, RefundMath.refund(100, WEEK, WEEK, 0));
        assertEquals(0, RefundMath.refund(100, WEEK, 0, 50));
    }

    @Test
    void percentIsCappedAtOneHundred() {
        assertEquals(100, RefundMath.refund(100, WEEK, WEEK, 500));
    }

    @Test
    void hugeValuesDoNotOverflow() {
        long big = RefundMath.refund(Long.MAX_VALUE / 4, 10 * WEEK, WEEK, 100);
        assertEquals(Long.MAX_VALUE / 2, big);
    }
}
