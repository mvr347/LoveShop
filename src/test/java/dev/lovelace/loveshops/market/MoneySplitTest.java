package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.Denomination;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoneySplitTest {

    private static final List<Denomination> DENS = List.of(
            new Denomination("ia:copper_coin", 1),
            new Denomination("ia:iron_coin", 10),
            new Denomination("ia:gold_coin", 50),
            new Denomination("ia:diamond_coin", 100),
            new Denomination("ia:netherite_coin", 1000));

    private static long total(List<MoneySplit.Part> parts) {
        return parts.stream().mapToLong(p -> p.denomination().value() * p.count()).sum();
    }

    @Test
    void zeroAndNegativeGiveNoCoins() {
        assertTrue(MoneySplit.split(0, DENS).isEmpty());
        assertTrue(MoneySplit.split(-5, DENS).isEmpty());
    }

    @Test
    void noDenominationsGiveNoCoins() {
        assertTrue(MoneySplit.split(100, List.of()).isEmpty());
        assertTrue(MoneySplit.split(100, null).isEmpty());
    }

    @Test
    void usesBiggestCoinsFirst() {
        List<MoneySplit.Part> parts = MoneySplit.split(1255, DENS);
        assertEquals("ia:netherite_coin", parts.get(0).denomination().itemId());
        assertEquals(1, parts.get(0).count());
        assertEquals(1255, total(parts));
        // 1000 + 2x100 + 1x50 + 0x10 + 5x1 -> netherite, diamond, gold, copper (no iron)
        assertEquals(4, parts.size());
        assertEquals(2, parts.get(1).count());
        assertEquals(5, parts.get(3).count());
    }

    @Test
    void boundariesAroundEveryDenomination() {
        for (long amount : new long[]{1, 9, 10, 11, 49, 50, 51, 99, 100, 101, 999, 1000, 1001, 123_456_789L}) {
            assertEquals(amount, total(MoneySplit.split(amount, DENS)), "amount " + amount);
        }
    }

    @Test
    void inputOrderDoesNotMatter() {
        List<Denomination> shuffled = List.of(DENS.get(3), DENS.get(0), DENS.get(4), DENS.get(2), DENS.get(1));
        assertEquals(MoneySplit.split(2345, DENS).size(), MoneySplit.split(2345, shuffled).size());
        assertEquals(2345, total(MoneySplit.split(2345, shuffled)));
    }

    @Test
    void remainderBelowSmallestCoinIsDropped() {
        List<Denomination> coarse = List.of(new Denomination("a", 10), new Denomination("b", 100));
        assertEquals(120, total(MoneySplit.split(125, coarse)));
    }

    @Test
    void hugeAmountDoesNotOverflow() {
        assertEquals(Long.MAX_VALUE / 1000 * 1000, total(MoneySplit.split(Long.MAX_VALUE / 1000 * 1000, DENS)));
    }

    @Test
    void smallestPicksTheLowestValue() {
        assertEquals("ia:copper_coin", MoneySplit.smallest(DENS).itemId());
        assertNull(MoneySplit.smallest(List.of()));
    }
}
