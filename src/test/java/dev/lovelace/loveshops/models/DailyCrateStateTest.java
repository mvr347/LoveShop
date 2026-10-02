package dev.lovelace.loveshops.models;

import dev.lovelace.loveshops.models.caravan.DailyCrateState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DailyCrateStateTest {

    @Test
    void testRemainingAndProgress() {
        DailyCrateState crate = new DailyCrateState(
                1, 10, "cobblestone_crate", "Ящик булыжника",
                128, 640, 2, false, 0, false
        );

        assertEquals(512, crate.remainingAmount());
        assertEquals(128, crate.currentAmount());
        assertEquals(640, crate.maxAmount());
        assertFalse(crate.isClosed());
    }

    @Test
    void testRemainingWhenFull() {
        DailyCrateState crate = new DailyCrateState(
                2, 10, "wheat_crate", "Ящик пшеницы",
                640, 640, 5, false, 0, true
        );

        assertEquals(0, crate.remainingAmount());
        assertEquals(640, crate.currentAmount());
        assertTrue(crate.isClosed());
    }

    @Test
    void testUrgentCrateState() {
        long expireTime = (System.currentTimeMillis() / 1000) + 1800;
        DailyCrateState crate = new DailyCrateState(
                3, 10, "iron_crate", "Срочный ящик железа",
                0, 320, 15, true, expireTime, false
        );

        assertTrue(crate.isUrgent());
        assertEquals(expireTime, crate.urgentExpiresAt());
        assertEquals(320, crate.remainingAmount());
    }
}
