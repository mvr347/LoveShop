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
}
