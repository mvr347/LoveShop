package dev.lovelace.loveshops.market.wizard;

import dev.lovelace.loveshops.market.wizard.PointWizard.Pos;
import dev.lovelace.loveshops.market.wizard.PointWizard.SetResult;
import dev.lovelace.loveshops.market.wizard.PointWizard.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PointWizardTest {

    private static Pos pos(String world, double x, double y, double z) {
        return new Pos(world, x, y, z, 0f, 0f);
    }

    private static PointWizard started() {
        PointWizard w = new PointWizard("a1", 100, 16);
        assertEquals(SetResult.OK, w.set(pos("world", 0, 64, 0)));
        assertEquals(SetResult.OK, w.set(pos("world", 5, 64, 4)));
        return w;
    }

    @Test
    void stepsFollowTheDocumentedOrder() {
        PointWizard w = new PointWizard("a1", 100, 16);
        assertEquals(Step.CORNER_1, w.step());
        w.set(pos("world", 0, 64, 0));
        assertEquals(Step.CORNER_2, w.step());
        w.set(pos("world", 5, 64, 4));
        assertEquals(Step.NPC, w.step());
        w.set(pos("world", 2, 64, 2));
        assertEquals(Step.CLOSED_SIGN, w.step());
        w.set(pos("world", 1, 65, 1));
        assertEquals(Step.ID_SIGN, w.step());
        w.set(pos("world", 1, 65, 3));
        assertEquals(Step.TELEPORT, w.step());
        w.set(pos("world", 6, 64, 6));
        assertEquals(Step.CONFIRM, w.step());
        assertTrue(w.ready());
    }

    @Test
    void optionalStepsCanBeSkippedRequiredOnesCannot() {
        PointWizard w = new PointWizard("a1", 100, 16);
        assertFalse(w.skip());
        w.set(pos("world", 0, 64, 0));
        assertFalse(w.skip());
        w.set(pos("world", 3, 64, 3));
        assertFalse(w.skip());
        w.set(pos("world", 1, 64, 1));
        assertTrue(w.skip());   // closed sign
        assertTrue(w.skip());   // id sign
        assertTrue(w.skip());   // teleport
        assertEquals(Step.CONFIRM, w.step());
        assertFalse(w.skip());
        assertTrue(w.ready());
        assertNull(w.closedSign());
        assertNull(w.teleport());
    }

    @Test
    void cornerInAnotherWorldIsRefused() {
        PointWizard w = new PointWizard("a1", 100, 16);
        w.set(pos("world", 0, 64, 0));
        assertEquals(SetResult.WRONG_WORLD, w.set(pos("nether", 1, 64, 1)));
        assertEquals(Step.CORNER_2, w.step());
    }

    @Test
    void footprintIsLimited() {
        PointWizard w = new PointWizard("a1", 100, 16);
        w.set(pos("world", 0, 64, 0));
        assertEquals(SetResult.TOO_LARGE, w.set(pos("world", 16, 64, 0)));   // 17 blocks wide
        assertEquals(SetResult.OK, w.set(pos("world", 15, 64, 15)));          // exactly 16x16
    }

    @Test
    void traderMustStandInsideTheZone() {
        PointWizard w = started();
        assertEquals(SetResult.OUTSIDE_ZONE, w.set(pos("world", 9, 64, 2)));
        assertEquals(Step.NPC, w.step());
        assertEquals(SetResult.OK, w.set(pos("world", 5, 70, 4)));            // height is not compared
    }

    @Test
    void zoneWorksWithCornersInAnyOrder() {
        PointWizard w = new PointWizard("a1", 100, 16);
        w.set(pos("world", 5, 64, 4));
        w.set(pos("world", 0, 64, 0));
        assertTrue(w.insideZone(pos("world", 2, 64, 2)));
        assertFalse(w.insideZone(pos("world", 6, 64, 2)));
        assertFalse(w.insideZone(pos("other", 2, 64, 2)));
    }

    @Test
    void backKeepsTheDataAndStopsAtTheFirstStep() {
        PointWizard w = started();
        assertTrue(w.back());
        assertEquals(Step.CORNER_2, w.step());
        assertNotNull(w.corner2());
        assertTrue(w.back());
        assertFalse(w.back());
        assertEquals(Step.CORNER_1, w.step());
    }

    @Test
    void missingListsTheRequiredParts() {
        PointWizard w = new PointWizard("a1", 100, 16);
        assertEquals(3, w.missing().size());
        w.set(pos("world", 0, 64, 0));
        w.set(pos("world", 1, 64, 1));
        assertEquals(java.util.List.of("npc"), w.missing());
        assertFalse(w.ready());
    }

    @Test
    void idsAreValidated() {
        assertTrue(PointWizard.validId("a1"));
        assertTrue(PointWizard.validId("Точка-7"));
        assertFalse(PointWizard.validId("with space"));
        assertFalse(PointWizard.validId(""));
        assertFalse(PointWizard.validId(null));
    }

    @Test
    void settingAtTheSummaryIsAWrongStep() {
        PointWizard w = started();
        w.set(pos("world", 2, 64, 2));
        w.skip();
        w.skip();
        w.skip();
        assertEquals(SetResult.WRONG_STEP, w.set(pos("world", 1, 1, 1)));
    }
}
