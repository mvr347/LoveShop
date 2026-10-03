package dev.lovelace.loveshops.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** gui_gen v2.1 invariants for the 54-slot lost-caravan auction menu. */
class LostCaravanAuctionGuiLayoutTest {

    private static List<Integer> contentSlots() {
        List<Integer> slots = new ArrayList<>(List.of(
                LostCaravanAuctionGui.SLOT_TIMER, LostCaravanAuctionGui.SLOT_LOT_PREVIEW, LostCaravanAuctionGui.SLOT_MY_STATUS,
                LostCaravanAuctionGui.SLOT_LEADER, LostCaravanAuctionGui.SLOT_MIN_BID,
                LostCaravanAuctionGui.SLOT_BID_1, LostCaravanAuctionGui.SLOT_BID_2, LostCaravanAuctionGui.SLOT_BID_3,
                LostCaravanAuctionGui.SLOT_CUSTOM_BID));
        for (int s : LostCaravanAuctionGui.SLOTS_QUEUE) slots.add(s);
        for (int s : LostCaravanAuctionGui.SLOTS_RECENT_BIDS) slots.add(s);
        return slots;
    }

    @Test
    void sizeIsStandard() {
        assertEquals(54, LostCaravanAuctionGui.SIZE);
    }

    @Test
    void contentStaysInWorkZoneAndOutOfSideWalls() {
        Set<Integer> seen = new HashSet<>();
        for (int slot : contentSlots()) {
            assertTrue(slot >= 18 && slot <= 44, "slot " + slot + " is outside the work zone 18-44");
            int column = slot % 9;
            assertTrue(column >= 1 && column <= 7, "slot " + slot + " is on a side wall");
            assertTrue(seen.add(slot), "slot " + slot + " is used twice");
        }
    }

    @Test
    void headerAndFooterAreFrameOnly() {
        assertEquals(0, LostCaravanAuctionGui.SLOT_INFO);
        assertEquals(53, LostCaravanAuctionGui.SLOT_CLOSE);
        assertFalse(contentSlots().contains(LostCaravanAuctionGui.SLOT_CLOSE));
        for (int slot : contentSlots()) {
            assertFalse(slot < 18 || slot > 44, "content slot " + slot + " is in Header/Row1/Footer");
        }
    }
}
