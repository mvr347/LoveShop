package dev.lovelace.loveshops.market.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceInputTest {

    private static final long[] COINS = {1, 10, 50, 100};

    @Test
    void startsUnsetAndCannotBeConfirmed() {
        PriceInput in = new PriceInput(COINS, 1, 1000, 0);
        assertEquals(0, in.price());
        assertFalse(in.valid());
    }

    @Test
    void leftAddsRightSubtractsActiveCoin() {
        PriceInput in = new PriceInput(COINS, 1, 1000, 0);
        in.add();
        in.add();
        assertEquals(2, in.price());
        in.cycle();
        in.add();
        assertEquals(12, in.price());
        in.subtract();
        assertEquals(2, in.price());
        assertTrue(in.valid());
    }

    @Test
    void cycleWrapsAround() {
        PriceInput in = new PriceInput(COINS, 1, 1000, 0);
        for (int i = 0; i < COINS.length; i++) in.cycle();
        assertEquals(0, in.activeIndex());
    }

    @Test
    void priceStaysInsideBounds() {
        PriceInput in = new PriceInput(COINS, 5, 120, 0);
        in.subtract();
        assertEquals(0, in.price());
        in.add();
        assertEquals(1, in.price());
        assertFalse(in.valid(), "below the minimum is not confirmable");
        for (int i = 0; i < 10; i++) in.cycle();
        for (int i = 0; i < 5; i++) in.add();
        assertEquals(120, in.price(), "capped at the maximum");
        assertTrue(in.valid());
    }

    @Test
    void editingKeepsTheCurrentPrice() {
        PriceInput in = new PriceInput(COINS, 1, 1000, 250);
        assertEquals(250, in.price());
        assertTrue(in.valid());
    }

    @Test
    void worksWithoutDenominations() {
        PriceInput in = new PriceInput(new long[0], 1, 10, 0);
        in.add();
        assertEquals(1, in.price());
        assertEquals(1, in.unitCount());
    }
}
