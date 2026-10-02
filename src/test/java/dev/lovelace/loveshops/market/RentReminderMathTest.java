package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RentReminderMathTest {

    private static final long H = 3_600_000L;
    private static final int[] T = {72, 24, 6, 1};

    @Test
    void nothingWhileFarFromTheEnd() {
        assertNull(RentReminderMath.decide(T, 100 * H, null).send());
    }

    @Test
    void firstCrossingSendsTheBiggestThreshold() {
        assertEquals(72, RentReminderMath.decide(T, 70 * H, null).send());
    }

    @Test
    void eachThresholdIsSentOnce() {
        assertNull(RentReminderMath.decide(T, 60 * H, 72).send());
        assertEquals(24, RentReminderMath.decide(T, 23 * H, 72).send());
        assertNull(RentReminderMath.decide(T, 20 * H, 24).send());
        assertEquals(1, RentReminderMath.decide(T, H / 2, 6).send());
    }

    @Test
    void severalCrossedAtOnceSendOnlyTheMostUrgent() {
        assertEquals(6, RentReminderMath.decide(T, 5 * H, null).send());
    }

    @Test
    void renewalRearmsTheReminders() {
        // 24 h was reminded, then the rent was paid: 7 days left again.
        RentReminderMath.Decision far = RentReminderMath.decide(T, 7 * 24 * H, 24);
        assertTrue(far.reset());
        assertNull(far.send());
        // A smaller renewal that still leaves more time than the last threshold: remind again at 24.
        assertEquals(24, RentReminderMath.decide(T, 23 * H, 1).send());
    }

    @Test
    void noReminderOncePastTheEnd() {
        assertNull(RentReminderMath.decide(T, 0, 1).send());
        assertNull(RentReminderMath.decide(T, -5 * H, null).send());
    }

    @Test
    void normalizeSortsAndFallsBack() {
        assertArrayEquals(new int[]{48, 12, 2}, RentReminderMath.normalize(List.of(12, 2, 48, 12, -3, 0)));
        assertArrayEquals(new int[]{72, 24, 6, 1}, RentReminderMath.normalize(List.of()));
        assertArrayEquals(new int[]{72, 24, 6, 1}, RentReminderMath.normalize(null));
    }
}
