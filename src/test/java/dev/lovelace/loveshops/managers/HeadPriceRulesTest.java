package dev.lovelace.loveshops.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.lovelace.loveshops.managers.HeadPriceRules.Params;
import dev.lovelace.loveshops.managers.HeadPriceRules.Subject;
import org.junit.jupiter.api.Test;

class HeadPriceRulesTest {
    // base 1000, 10/hour up to 500 h, 20/kill up to 200, reputation x0.7..x1.4, +40% notoriety, 200..10000
    private static final Params P = new Params(1000, 10, 500, 20, 200, 0.7, 1.4, 0.4, 200, 10000);

    @Test
    void priceGrowsWithPlayTimeAndKills() {
        long fresh = HeadPriceRules.price(new Subject(0, 0, 50, -1, 6), P);
        long veteran = HeadPriceRules.price(new Subject(50, 20, 50, -1, 6), P);
        assertEquals(Math.round(1000 * 1.05), fresh);
        assertEquals(Math.round((1000 + 500 + 400) * 1.05), veteran);
        assertTrue(veteran > fresh);
    }

    @Test
    void playTimeAndKillsStopCountingAtTheirCaps() {
        long atCap = HeadPriceRules.price(new Subject(500, 200, 50, -1, 6), P);
        long beyond = HeadPriceRules.price(new Subject(9000, 9000, 50, -1, 6), P);
        assertEquals(atCap, beyond);
    }

    @Test
    void reputationScalesLinearlyAndUnknownCountsAsTheMiddle() {
        Subject low = new Subject(100, 0, 0, -1, 6);
        Subject high = new Subject(100, 0, 100, -1, 6);
        Subject unknown = new Subject(100, 0, -1, -1, 6);
        assertEquals(Math.round(2000 * 0.7), HeadPriceRules.price(low, P));
        assertEquals(Math.round(2000 * 1.4), HeadPriceRules.price(high, P));
        assertEquals(Math.round(2000 * 1.05), HeadPriceRules.price(unknown, P));
    }

    @Test
    void onlyAggressivePlayersCarryNotoriety() {
        long aggressive = HeadPriceRules.price(new Subject(100, 0, 50, 0, 6), P);
        long neutral = HeadPriceRules.price(new Subject(100, 0, 50, 3, 6), P);
        long kind = HeadPriceRules.price(new Subject(100, 0, 50, 6, 6), P);
        assertEquals(Math.round(2000 * 1.05 * 1.4), aggressive);
        assertEquals(neutral, kind);
        assertEquals(Math.round(2000 * 1.05), neutral);
    }

    @Test
    void priceIsClampedToTheFloorAndTheCeiling() {
        Params floor = new Params(0, 0, 0, 0, 0, 0.7, 1.4, 0, 200, 10000);
        assertEquals(200, HeadPriceRules.price(new Subject(0, 0, 0, -1, 6), floor));
        assertEquals(10000, HeadPriceRules.price(new Subject(500, 200, 100, 0, 6), new Params(1000, 100, 500, 100, 200, 0.7, 1.4, 0.4, 200, 10000)));
        Params noCeiling = new Params(1000, 100, 500, 100, 200, 1, 1, 0, 0, 0);
        assertEquals(1000 + 100 * 500 + 100 * 200, HeadPriceRules.price(new Subject(500, 200, 50, -1, 6), noCeiling));
    }

    @Test
    void cooldownCountsDownAndEnds() {
        long day = 86_400_000L;
        assertEquals(7 * day, HeadPriceRules.cooldownLeft(1000, 1000, 7 * day));
        assertEquals(day, HeadPriceRules.cooldownLeft(1000, 1000 + 6 * day, 7 * day));
        assertEquals(0, HeadPriceRules.cooldownLeft(1000, 1000 + 7 * day, 7 * day));
        assertEquals(0, HeadPriceRules.cooldownLeft(0, 5000, 7 * day));
        assertEquals(0, HeadPriceRules.cooldownLeft(1000, 1500, 0));
    }

    // causes: PROJECTILE x1.2, FALL x0.8; weapons: NETHERITE x1.2, BOW x1.1, FIST x1.25; armor +2%/pt up to 30%,
    // hunted +5%/day up to 25%; reward 10% up to 300
    private static final HeadPriceRules.Circumstances C = new HeadPriceRules.Circumstances(
            java.util.Map.of("PROJECTILE", 1.2, "FALL", 0.8), 1.0,
            java.util.Map.of("NETHERITE", 1.2, "BOW", 1.1, "FIST", 1.25), 1.0,
            0.02, 0.30, 0.10, 300L, 0.05, 0.25);
    private static final Params PC = new Params(1000, 0, 0, 0, 0, 1.0, 1.0, 0, 0, 0, C);

    private static Subject died(HeadPriceRules.Details details) {
        return new Subject(0, 0, 50, -1, 6, details);
    }

    @Test
    void anOldHeadWithoutDetailsIsPricedWithoutCircumstances() {
        assertEquals(1000, HeadPriceRules.price(died(null), PC));
    }

    @Test
    void causeAndWeaponMultiplyAndWeaponIsFoundByPrefix() {
        assertEquals(1000 * 1.2 * 1.2, HeadPriceRules.price(died(new HeadPriceRules.Details("PROJECTILE", "NETHERITE_SWORD", 0, 0, 0)), PC), 1.0);
        assertEquals(1000 * 0.8 * 1.25, HeadPriceRules.price(died(new HeadPriceRules.Details("FALL", "FIST", 0, 0, 0)), PC), 1.0);
        // an unknown cause and weapon fall back to the defaults
        assertEquals(1000, HeadPriceRules.price(died(new HeadPriceRules.Details("WITHER", "STICK", 0, 0, 0)), PC));
    }

    @Test
    void armorAndTheLengthOfTheHuntAddUpToTheirCaps() {
        assertEquals(1000 * 1.2, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 10, 0, 0)), PC), 1.0);
        assertEquals(1000 * 1.3, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 20, 0, 0)), PC), 1.0);
        assertEquals(1000 * 1.3, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 400, 0, 0)), PC), 1.0);
        assertEquals(1000 * 1.1, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 0, 0, 2)), PC), 1.0);
        assertEquals(1000 * 1.25, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 0, 0, 60)), PC), 1.0);
        assertEquals(1000 * 1.55, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 20, 0, 60)), PC), 1.0);
    }

    @Test
    void theRewardAddsAShareWithACap() {
        assertEquals(1000 + 100, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 0, 1000, 0)), PC));
        assertEquals(1000 + 300, HeadPriceRules.price(died(new HeadPriceRules.Details("X", "X", 0, 5_000_000, 0)), PC));
    }

    @Test
    void parsesTheTagLoveHuntWrites() {
        var d = HeadPriceRules.Details.parse("cause=PROJECTILE;weapon=BOW;armor=18.5;reward=4000;hunted=172800000");
        assertEquals("PROJECTILE", d.cause());
        assertEquals("BOW", d.weapon());
        assertEquals(18.5, d.armor(), 1e-9);
        assertEquals(4000L, d.reward());
        assertEquals(2.0, d.huntedDays(), 1e-9);
        assertEquals(null, HeadPriceRules.Details.parse(null));
        var damaged = HeadPriceRules.Details.parse("cause=FALL;armor=oops;reward=-5;hunted=abc");
        assertEquals(0.0, damaged.armor());
        assertEquals(0L, damaged.reward());
        assertEquals(0.0, damaged.huntedDays());
    }
}
