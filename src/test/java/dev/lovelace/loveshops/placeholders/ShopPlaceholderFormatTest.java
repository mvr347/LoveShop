package dev.lovelace.loveshops.placeholders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.lovelace.loveshops.managers.ShopStatsCache;
import org.junit.jupiter.api.Test;

class ShopPlaceholderFormatTest {
    private static final ShopStatsCache.Values V = new ShopStatsCache.Values(2, 40, 900, 4.5, 6, 3, 70, 12);

    @Test
    void numbersAreShownPlain() {
        assertEquals("2", LoveShopsPlaceholder.format("points", V));
        assertEquals("40", LoveShopsPlaceholder.format("sales_total", V));
        assertEquals("900", LoveShopsPlaceholder.format("revenue_total", V));
        assertEquals("3", LoveShopsPlaceholder.format("trader_level", V));
        assertEquals("70", LoveShopsPlaceholder.format("total_sold", V));
        assertEquals("12", LoveShopsPlaceholder.format("total_bought", V));
    }

    @Test
    void theRatingHasOneDecimalAndADashWhenNobodyRatedYet() {
        assertEquals("4.5", LoveShopsPlaceholder.format("rating", V));
        assertEquals("—", LoveShopsPlaceholder.format("rating", ShopStatsCache.Values.EMPTY));
    }

    @Test
    void theEmptyValuesAreZerosAndLevelOne() {
        assertEquals("0", LoveShopsPlaceholder.format("points", ShopStatsCache.Values.EMPTY));
        assertEquals("1", LoveShopsPlaceholder.format("trader_level", ShopStatsCache.Values.EMPTY));
    }
}
