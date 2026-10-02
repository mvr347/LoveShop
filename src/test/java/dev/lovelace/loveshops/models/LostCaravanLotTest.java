package dev.lovelace.loveshops.models;

import dev.lovelace.loveshops.models.caravan.LostCaravanLot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LostCaravanLotTest {

    @Test
    void testStartingPriceAndInitialBid() {
        LostCaravanLot lot = new LostCaravanLot(
                1, 5, 0, false, null,
                50, 0, null, "ACTIVE",
                System.currentTimeMillis() / 1000, 0
        );

        assertEquals(50, lot.startingPrice());
        assertEquals(0, lot.currentBid());
        assertNull(lot.highestBidder());
        assertFalse(lot.secret());
        assertEquals("ACTIVE", lot.status());
    }

    @Test
    void testSecretCrateLot() {
        UUID bidder = UUID.randomUUID();
        LostCaravanLot lot = new LostCaravanLot(
                2, 5, 5, true, null,
                100, 250, bidder, "ACTIVE",
                System.currentTimeMillis() / 1000, 0
        );

        assertTrue(lot.secret());
        assertEquals(bidder, lot.highestBidder());
        assertEquals(250, lot.currentBid());

        // Min next bid rule: currentBid + max(1, 5% of currentBid)
        int minNextBid = lot.currentBid() + Math.max(1, (int) Math.round(lot.currentBid() * 0.05));
        assertEquals(250 + 13, minNextBid); // 250 * 0.05 = 12.5 -> 13
    }
}
