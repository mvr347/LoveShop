package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import static dev.lovelace.loveshops.market.ClaimSync.Verdict.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimSyncTest {
    @Test
    void anUnreachableLoveClaimsNeverDeletesAnything() {
        assertEquals(UNAVAILABLE, ClaimSync.orphans(false, 0, 5, true));
    }

    @Test
    void anEmptyAnswerKeepsEveryPointEvenOnTheSecondLook() {
        assertEquals(KEEP_NO_DATA, ClaimSync.orphans(true, 0, 3, false));
        assertEquals(KEEP_NO_DATA, ClaimSync.orphans(true, 0, 3, true));
    }

    @Test
    void aMissingPointIsOnlyForgottenAfterTheSecondLook() {
        assertEquals(RECHECK_LATER, ClaimSync.orphans(true, 4, 1, false));
        assertEquals(FORGET, ClaimSync.orphans(true, 4, 1, true));
    }

    @Test
    void nothingMissingNeedsNoAction() {
        assertEquals(NOTHING_MISSING, ClaimSync.orphans(true, 4, 0, false));
    }
}
