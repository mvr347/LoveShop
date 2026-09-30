package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DailyBuyerMathTest {

    @Test
    void windowStartsAtStartHourAndLastsItsDuration() {
        // 18:00 + 6 h = 18:00 .. 24:00 (the end is exclusive)
        assertFalse(DailyBuyerMath.isActive(17 * 60 + 59, 18, 6));
        assertTrue(DailyBuyerMath.isActive(18 * 60, 18, 6));
        assertTrue(DailyBuyerMath.isActive(23 * 60 + 59, 18, 6));
        assertFalse(DailyBuyerMath.isActive(0, 18, 6));
        assertFalse(DailyBuyerMath.isActive(7 * 60 + 1, 18, 6));
    }

    @Test
    void windowRunningPastMidnightIsHandled() {
        assertTrue(DailyBuyerMath.isActive(1 * 60, 22, 6));   // 01:00 is inside 22:00..04:00
        assertTrue(DailyBuyerMath.isActive(3 * 60 + 59, 22, 6));
        assertFalse(DailyBuyerMath.isActive(4 * 60, 22, 6));
        assertFalse(DailyBuyerMath.isActive(12 * 60, 22, 6));
    }

    @Test
    void zeroAndFullDurations() {
        assertFalse(DailyBuyerMath.isActive(600, 18, 0));
        assertTrue(DailyBuyerMath.isActive(600, 18, 24));
        assertTrue(DailyBuyerMath.isActive(600, 18, 99)); // clamped to 24
    }

    @Test
    void negativeStartHourWraps() {
        assertTrue(DailyBuyerMath.isActive(23 * 60, -1, 2)); // start 23:00
    }

    @Test
    void sameDayAlwaysGivesTheSameCategories() {
        List<String> names = List.of("ORES", "FOOD", "MOB_DROPS", "FARM", "WOOD");
        assertEquals(DailyBuyerMath.pickCategories("2026-09-30", names, 2), DailyBuyerMath.pickCategories("2026-09-30", names, 2));
        // input order must not matter
        assertEquals(DailyBuyerMath.pickCategories("2026-09-30", names, 2),
                DailyBuyerMath.pickCategories("2026-09-30", List.of("WOOD", "FARM", "MOB_DROPS", "FOOD", "ORES"), 2));
    }

    @Test
    void categoriesRotateAcrossDays() {
        List<String> names = List.of("ORES", "FOOD", "MOB_DROPS", "FARM", "WOOD");
        java.util.Set<List<String>> seen = new java.util.HashSet<>();
        for (int d = 1; d <= 20; d++) seen.add(DailyBuyerMath.pickCategories("2026-09-" + String.format("%02d", d), names, 2));
        assertTrue(seen.size() > 3, "picks should vary over 20 days, got " + seen.size());
    }

    @Test
    void pickRespectsCountAndAvailability() {
        assertEquals(2, DailyBuyerMath.pickCategories("d", List.of("A", "B", "C"), 2).size());
        assertEquals(3, DailyBuyerMath.pickCategories("d", List.of("A", "B", "C"), 9).size());
        assertTrue(DailyBuyerMath.pickCategories("d", List.of(), 2).isEmpty());
        assertTrue(DailyBuyerMath.pickCategories("d", List.of("A"), 0).isEmpty());
    }

    @Test
    void allowanceNeverGoesNegative() {
        assertEquals(256, DailyBuyerMath.remainingAllowance(256, 0));
        assertEquals(0, DailyBuyerMath.remainingAllowance(256, 256));
        assertEquals(0, DailyBuyerMath.remainingAllowance(256, 500));
        assertEquals(256, DailyBuyerMath.remainingAllowance(256, -5));
    }
}
