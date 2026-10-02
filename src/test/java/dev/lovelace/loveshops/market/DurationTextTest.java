package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationTextTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;

    @Test
    void usesTwoBiggestUnits() {
        assertEquals("3 д 4 ч", DurationText.format(3 * DAY + 4 * HOUR + 30 * MIN));
        assertEquals("5 ч 20 мин", DurationText.format(5 * HOUR + 20 * MIN));
        assertEquals("12 мин", DurationText.format(12 * MIN + 30_000L));
    }

    @Test
    void dropsZeroLowerUnit() {
        assertEquals("2 д", DurationText.format(2 * DAY));
        assertEquals("7 ч", DurationText.format(7 * HOUR));
    }

    @Test
    void lessThanAMinuteAndNegative() {
        assertEquals("меньше минуты", DurationText.format(30_000L));
        assertEquals("меньше минуты", DurationText.format(-5 * HOUR));
    }
}
