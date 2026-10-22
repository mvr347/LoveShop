package dev.lovelace.loveshops.npc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NpcKeysTest {

    @Test
    void numericKeysAreLegacyCitizensIds() {
        assertEquals(12, NpcKeys.legacyIdOf("12"));
        assertEquals(0, NpcKeys.legacyIdOf(" 0 "));
        assertEquals("5", NpcKeys.ofLegacyId(5));
        assertNull(NpcKeys.ofLegacyId(null));
    }

    @Test
    void otherKeysAreNotNumbers() {
        assertNull(NpcKeys.legacyIdOf("ext-12ab34cd"));
        assertNull(NpcKeys.legacyIdOf("-3"));
        assertNull(NpcKeys.legacyIdOf("1.5"));
        assertNull(NpcKeys.legacyIdOf(""));
        assertNull(NpcKeys.legacyIdOf(null));
        assertNull(NpcKeys.legacyIdOf("99999999999"));
        assertNull(NpcKeys.legacyIdOf("١٢"));
    }

    @Test
    void keyWinsOverLegacyId() {
        assertEquals("ext-1", NpcKeys.resolve("ext-1", 7));
        assertEquals("7", NpcKeys.resolve(null, 7));
        assertEquals("7", NpcKeys.resolve("  ", 7));
        assertNull(NpcKeys.resolve(null, null));
    }
}
