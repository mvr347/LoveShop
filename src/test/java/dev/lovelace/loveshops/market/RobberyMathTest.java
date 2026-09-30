package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RobberyMathTest {

    private static final double BASE = 0.01;
    private static final double STEP = 0.005;
    private static final double CAP = 0.20;

    @Test
    void chanceGrowsWithEveryClickAndIsCapped() {
        double prev = 0;
        for (int k = 1; k <= 12; k++) {
            double p = RobberyMath.chance(k, BASE, STEP, CAP);
            assertTrue(p > prev, "click " + k);
            assertTrue(p <= CAP);
            prev = p;
        }
        assertEquals(0.01, RobberyMath.chance(1, BASE, STEP, CAP), 1e-12);
        assertEquals(0.065, RobberyMath.chance(12, BASE, STEP, CAP), 1e-12);
        assertEquals(CAP, RobberyMath.chance(1000, BASE, STEP, CAP), 1e-12);
    }

    @Test
    void chanceIsZeroBeforeTheFirstClickAndNeverNegative() {
        assertEquals(0.0, RobberyMath.chance(0, BASE, STEP, CAP));
        assertEquals(0.0, RobberyMath.chance(3, -1.0, 0.0, CAP));
    }

    @Test
    void aLoneRobbersDayIsAboutThirtySevenPercent() {
        double day = RobberyMath.dailyChance(12, BASE, STEP, CAP);
        assertEquals(0.369, day, 0.001);
    }

    @Test
    void twoRobbersAreAlmostDoubleButLess() {
        double p = 0.065;
        double two = RobberyMath.combined(List.of(p, p), 0.90);
        assertEquals(2 * p - p * p, two, 1e-12);
        assertTrue(two < 2 * p);
        assertTrue(two > p);
    }

    @Test
    void everyExtraRobberyAddsLessThanTheLast() {
        double p = 0.10;
        List<Double> chances = new ArrayList<>();
        double previousTotal = 0;
        double previousGain = Double.MAX_VALUE;
        for (int n = 1; n <= 40; n++) {
            chances.add(p);
            double total = RobberyMath.combined(chances, 1.0);
            double gain = total - previousTotal;
            assertTrue(gain > 0, "n=" + n);
            assertTrue(gain < previousGain, "gain must shrink, n=" + n);
            assertTrue(total < 1.0, "never certain, n=" + n);
            previousTotal = total;
            previousGain = gain;
        }
    }

    @Test
    void combinedIsCappedEvenForACrowd() {
        List<Double> crowd = new ArrayList<>();
        for (int i = 0; i < 100; i++) crowd.add(0.2);
        assertEquals(0.90, RobberyMath.combined(crowd, 0.90), 1e-12);
    }

    @Test
    void combinedIgnoresNullsAndClampsInputs() {
        assertEquals(0.0, RobberyMath.combined(List.of(), 0.9));
        List<Double> odd = new ArrayList<>();
        odd.add(null);
        odd.add(-0.5);
        odd.add(2.0);
        assertEquals(0.9, RobberyMath.combined(odd, 0.9), 1e-12);
    }

    @Test
    void dayRollsOverAtTheResetHourNotAtMidnight() {
        ZoneId utc = ZoneOffset.UTC;
        // 2026-09-30 03:59 is still "yesterday" for a 04:00 reset; 04:00 starts the new day.
        long before = java.time.LocalDateTime.of(2026, 9, 30, 3, 59).toInstant(ZoneOffset.UTC).toEpochMilli();
        long after = java.time.LocalDateTime.of(2026, 9, 30, 4, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
        assertEquals("2026-09-29", RobberyMath.dayKey(before, 4, utc));
        assertEquals("2026-09-30", RobberyMath.dayKey(after, 4, utc));
        assertEquals("2026-09-30", RobberyMath.dayKey(before, 0, utc));
    }

    @Test
    void dayStartMatchesItsKey() {
        ZoneId utc = ZoneOffset.UTC;
        long start = RobberyMath.dayStartMillis("2026-09-30", 4, utc);
        assertEquals("2026-09-30", RobberyMath.dayKey(start, 4, utc));
        assertEquals("2026-09-29", RobberyMath.dayKey(start - 1, 4, utc));
    }

    @Test
    void patienceStagesFallAsClicksAccumulate() {
        assertEquals(0, RobberyMath.stage(RobberyMath.patience(1, 12)));
        assertEquals(1, RobberyMath.stage(RobberyMath.patience(6, 12)));
        assertEquals(2, RobberyMath.stage(RobberyMath.patience(11, 12)));
        assertEquals(0.0, RobberyMath.patience(12, 12));
        assertEquals(0.0, RobberyMath.patience(1, 0));
    }

    @Test
    void coinLootIsAPercentOfTheTillWithAFloorOfOne() {
        assertEquals(150, RobberyMath.coinLoot(1000, 15));
        assertEquals(1, RobberyMath.coinLoot(3, 15));
        assertEquals(0, RobberyMath.coinLoot(0, 15));
        assertEquals(5, RobberyMath.coinLoot(5, 500)); // percent clamped to 100
    }

    @Test
    void shelvesToTakeFollowPercentWithinLimits() {
        assertEquals(1, RobberyMath.itemShelvesToTake(5, 10, 8));   // ceil(0.5) = 1
        assertEquals(2, RobberyMath.itemShelvesToTake(14, 10, 8));  // ceil(1.4) = 2
        assertEquals(8, RobberyMath.itemShelvesToTake(200, 100, 8)); // capped by maxItems
        assertEquals(0, RobberyMath.itemShelvesToTake(0, 10, 8));
        assertEquals(3, RobberyMath.itemShelvesToTake(3, 100, 8));
    }
}
