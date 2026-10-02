package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.market.gui.MarketLayout;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The gui_gen v2.1 slot rules, checked as numbers. */
class MarketLayoutTest {

    @Test
    void controlSlotsFollowRule4() {
        assertArrayEquals(new int[]{4}, MarketLayout.controlSlots(1));
        assertArrayEquals(new int[]{3, 5}, MarketLayout.controlSlots(2));
        assertArrayEquals(new int[]{2, 4, 6}, MarketLayout.controlSlots(3));
        assertArrayEquals(new int[]{2, 3, 5, 6}, MarketLayout.controlSlots(4));
        assertArrayEquals(new int[]{2, 3, 4, 6, 7}, MarketLayout.controlSlots(5));
        assertArrayEquals(new int[]{2, 3, 4, 5, 6, 7}, MarketLayout.controlSlots(6));
    }

    @Test
    void controlSlotsStayInsideTwoToSeven() {
        for (int n = 1; n <= 6; n++) {
            for (int slot : MarketLayout.controlSlots(n)) {
                assertTrue(slot >= 2 && slot <= 7, "count " + n + " slot " + slot);
            }
        }
    }

    @Test
    void controlSlotsRejectOutOfRangeCounts() {
        assertThrows(IllegalArgumentException.class, () -> MarketLayout.controlSlots(0));
        assertThrows(IllegalArgumentException.class, () -> MarketLayout.controlSlots(7));
    }

    @Test
    void contentZoneSizes() {
        assertEquals(7, MarketLayout.contentSlots(27).length);
        assertEquals(14, MarketLayout.contentSlots(36).length);
        assertEquals(14, MarketLayout.contentSlots(45).length);
        assertEquals(21, MarketLayout.contentSlots(54).length);
    }

    @Test
    void contentNeverTouchesFrameOrSideWalls() {
        for (int size : new int[]{27, 36, 45, 54}) {
            int workStart = MarketLayout.workStart(size);
            int footerStart = size - 9;
            for (int slot : MarketLayout.contentSlots(size)) {
                assertTrue(slot >= workStart && slot < footerStart, "size " + size + " slot " + slot);
                int col = slot % 9;
                assertTrue(col >= 1 && col <= 7, "side wall used: size " + size + " slot " + slot);
            }
        }
    }

    @Test
    void row1BelongsToTheHeaderFromFortyFiveSlots() {
        assertEquals(9, MarketLayout.workStart(27));
        assertEquals(9, MarketLayout.workStart(36));
        assertEquals(18, MarketLayout.workStart(45));
        assertEquals(18, MarketLayout.workStart(54));
    }

    @Test
    void footerSlotsAreTheLastRow() {
        assertEquals(51, MarketLayout.extraSlot(54));
        assertEquals(52, MarketLayout.backSlot(54));
        assertEquals(53, MarketLayout.closeSlot(54));
        assertEquals(24, MarketLayout.extraSlot(27));
        assertEquals(26, MarketLayout.closeSlot(27));
    }

    @Test
    void fiftyFourSlotContentMatchesTheStandardTable() {
        int[] expected = new int[21];
        int i = 0;
        for (int row : new int[]{18, 27, 36}) for (int col = 1; col <= 7; col++) expected[i++] = row + col;
        assertArrayEquals(expected, MarketLayout.contentSlots(54), Arrays.toString(MarketLayout.contentSlots(54)));
    }

    @Test
    void sizeForContentPicksSmallestStandardMenu() {
        assertEquals(27, MarketLayout.sizeForContent(1));
        assertEquals(27, MarketLayout.sizeForContent(7));
        assertEquals(36, MarketLayout.sizeForContent(8));
        assertEquals(36, MarketLayout.sizeForContent(14));
        assertEquals(54, MarketLayout.sizeForContent(15));
        assertEquals(54, MarketLayout.sizeForContent(100));
    }

    @Test
    void everyChosenSizeHoldsItsContent() {
        for (int shelves = 1; shelves <= 14; shelves++) {
            int size = MarketLayout.sizeForContent(shelves);
            assertTrue(MarketLayout.contentSlots(size).length >= shelves, "shelves " + shelves + " size " + size);
        }
    }

    @Test
    void pagerSlotsAreSideWallsNotContent() {
        // gui_gen rule 6: arrows live on slots 36 and 44 of a 54-slot menu, outside the content slots.
        for (int slot : MarketLayout.contentSlots(54)) {
            assertNotEquals(36, slot);
            assertNotEquals(44, slot);
        }
    }
}
