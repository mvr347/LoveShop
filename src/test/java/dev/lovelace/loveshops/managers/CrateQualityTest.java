package dev.lovelace.loveshops.managers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrateQualityTest {
    @Test
    void defaultsMatchOldHardcodedThresholds() {
        var d = CrateQuality.defaults(false);
        assertEquals("bad", CrateQuality.pick(d, 0.29).id());
        assertEquals("normal", CrateQuality.pick(d, 0.30).id());
        assertEquals("normal", CrateQuality.pick(d, 0.79).id());
        assertEquals("good", CrateQuality.pick(d, 0.80).id());
        var s = CrateQuality.defaults(true);
        assertEquals("bad", CrateQuality.pick(s, 0.14).id());
        assertEquals("normal", CrateQuality.pick(s, 0.54).id());
        assertEquals("good", CrateQuality.pick(s, 0.55).id());
    }

    @Test
    void weightsNeedNotSumToOne() {
        var tiers = List.of(new CrateQuality("bad", 1, 1, 1), new CrateQuality("good", 3, 1, 1));
        assertEquals("bad", CrateQuality.pick(tiers, 0.2).id());
        assertEquals("good", CrateQuality.pick(tiers, 0.3).id());
    }

    @Test
    void missingSectionFallsBackToDefaults() {
        assertEquals(CrateQuality.defaults(true), CrateQuality.fromConfig(null, true));
    }
}
