package dev.lovelace.loveshops.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PriceJitterTest {
    @Test
    void middleRollKeepsBase() {
        assertEquals(800, PriceJitter.apply(800, 25, 0.5));
    }

    @Test
    void edgesStayWithinPercent() {
        assertEquals(600, PriceJitter.apply(800, 25, 0.0));
        assertTrue(PriceJitter.apply(800, 25, 0.999999) <= 1000);
    }

    @Test
    void zeroPercentIsFixed() {
        assertEquals(800, PriceJitter.apply(800, 0, 0.1));
    }
}
