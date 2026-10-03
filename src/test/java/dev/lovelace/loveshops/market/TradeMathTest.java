package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.market.model.PlayerClass;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TradeMathTest {

    private static final ReputationRules.Thresholds T = new ReputationRules.Thresholds(1, 2, 6, 5);

    // ---- reputation classes

    @Test
    void outcastBeatsEverything() {
        assertEquals(PlayerClass.OUTCAST, ReputationRules.classify(0, 6, T));
        assertEquals(PlayerClass.OUTCAST, ReputationRules.classify(1, 0, T));
        assertEquals(PlayerClass.OUTCAST, ReputationRules.classify(1, 6, T));
    }

    @Test
    void aggressorIsCheckedAfterOutcast() {
        assertEquals(PlayerClass.AGGRESSOR, ReputationRules.classify(2, 0, T));
        assertEquals(PlayerClass.AGGRESSOR, ReputationRules.classify(6, 2, T));
        assertEquals(PlayerClass.NORMAL, ReputationRules.classify(3, 3, T));
    }

    @Test
    void perfectNeedsBothScales() {
        assertEquals(PlayerClass.PERFECT, ReputationRules.classify(6, 5, T));
        assertEquals(PlayerClass.PERFECT, ReputationRules.classify(6, 6, T));
        assertEquals(PlayerClass.NORMAL, ReputationRules.classify(6, 4, T));
        assertEquals(PlayerClass.NORMAL, ReputationRules.classify(5, 6, T));
    }

    @Test
    void neutralPlayerIsNormal() {
        assertEquals(PlayerClass.NORMAL, ReputationRules.classify(3, 3, T));
    }

    @Test
    void onlyNormalAndPerfectMayRentOrOperate() {
        assertTrue(ReputationRules.canRent(PlayerClass.NORMAL));
        assertTrue(ReputationRules.canRent(PlayerClass.PERFECT));
        assertFalse(ReputationRules.canRent(PlayerClass.OUTCAST));
        assertFalse(ReputationRules.canRent(PlayerClass.AGGRESSOR));
        assertFalse(ReputationRules.canOperate(PlayerClass.AGGRESSOR));
    }

    // ---- tax

    @Test
    void taxAndNetAlwaysAddUpToTotal() {
        for (long total : new long[]{1, 2, 3, 99, 100, 101, 12_345, 999_999_999L, 100_000_000L * 4096}) {
            for (double rate : new double[]{0.0, 0.05, 0.1, 0.375, 0.5, 0.75, 1.0}) {
                TaxMath.Split s = TaxMath.split(total, rate);
                assertEquals(total, s.tax() + s.net(), "total " + total + " rate " + rate);
                assertTrue(s.tax() >= 0 && s.net() >= 0, "total " + total + " rate " + rate);
            }
        }
    }

    @Test
    void zeroRateTakesNothing() {
        assertEquals(0, TaxMath.split(12345, 0.0).tax());
    }

    @Test
    void fullRateTakesEverything() {
        TaxMath.Split s = TaxMath.split(500, 1.0);
        assertEquals(500, s.tax());
        assertEquals(0, s.net());
    }

    @Test
    void rateIsClampedAndNaNIsZero() {
        assertEquals(0, TaxMath.split(1000, -0.5).tax());
        assertEquals(1000, TaxMath.split(1000, 7.0).tax());
        assertEquals(0, TaxMath.split(1000, Double.NaN).tax());
    }

    @Test
    void roundsHalfUp() {
        assertEquals(1, TaxMath.split(10, 0.05).tax()); // 0.5 -> 1
        assertEquals(0, TaxMath.split(9, 0.05).tax());  // 0.45 -> 0
    }

    @Test
    void hugeTotalsDoNotLoseCoins() {
        long total = 200_000_000_000_000_000L;
        TaxMath.Split s = TaxMath.split(total, 0.05);
        assertEquals(10_000_000_000_000_000L, s.tax());
        assertEquals(total, s.tax() + s.net());
    }

    @Test
    void nonPositiveTotalsGiveNoTax() {
        assertEquals(0, TaxMath.split(0, 0.5).tax());
        assertEquals(0, TaxMath.split(-10, 0.5).tax());
    }

    // ---- upgrades

    @Test
    void upgradeCostGrowsLinearlyInBiggestCoins() {
        assertEquals(1000, UpgradeMath.linearCost(1, 1000, 1, 1));
        assertEquals(2000, UpgradeMath.linearCost(2, 1000, 1, 1));
        assertEquals(16000, UpgradeMath.linearCost(16, 1000, 1, 1));
    }

    @Test
    void upgradeCostNeverOverflowsAndNeedsACoin() {
        assertEquals(1000, UpgradeMath.linearCost(0, 1000, 1, 1));
        assertEquals(0, UpgradeMath.linearCost(3, 0, 1, 1));
        assertTrue(UpgradeMath.linearCost(Integer.MAX_VALUE, Long.MAX_VALUE / 4, 1, 1) > 0);
    }

    @Test
    void maxLevelFillsTheBiggestMenu() {
        // 21 shelves in a 54-slot menu, 5 at level 1 and one more per level: level 17.
        assertEquals(17, UpgradeMath.maxLevel(21, 5));
        assertEquals(21, UpgradeMath.slots(5, UpgradeMath.maxLevel(21, 5)));
        assertEquals(1, UpgradeMath.maxLevel(21, 40));
    }

    @Test
    void slotsGrowByOnePerLevel() {
        assertEquals(5, UpgradeMath.slots(5, 1));
        assertEquals(6, UpgradeMath.slots(5, 2));
        assertEquals(14, UpgradeMath.slots(5, 10));
        assertEquals(5, UpgradeMath.slots(5, 0));
    }

    // ---- rating text

    @Test
    void ratingCommentIsStrippedOfTagsAndPlaceholders() {
        assertEquals("hello world", RatingService.sanitize("hello  world", 100));
        String tagged = RatingService.sanitize("<click:run_command:/op me>evil</click>", 100);
        assertFalse(tagged.contains("<") || tagged.contains(">"));
        assertFalse(RatingService.sanitize("%player_name% &cred §k", 100).matches(".*[%&§].*"));
        assertEquals("", RatingService.sanitize(null, 100));
        assertEquals("abc", RatingService.sanitize("abcdef", 3));
        assertEquals("a b", RatingService.sanitize("a\n\tb", 100));
    }
}
