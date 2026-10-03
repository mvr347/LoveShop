package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocationCodecTest {

    @Test
    void roundTripKeepsEveryPart() {
        String text = LocationCodec.encode("world_nether", 10.5, 64.0, -20.25, 90.0f, -12.5f);
        LocationCodec.Parts parts = LocationCodec.parse(text);
        assertNotNull(parts);
        assertEquals("world_nether", parts.world());
        assertEquals(10.5, parts.x(), 1e-6);
        assertEquals(64.0, parts.y(), 1e-6);
        assertEquals(-20.25, parts.z(), 1e-6);
        assertEquals(90.0f, parts.yaw(), 1e-6);
        assertEquals(-12.5f, parts.pitch(), 1e-6);
    }

    @Test
    void damagedValuesGiveNull() {
        assertNull(LocationCodec.parse(null));
        assertNull(LocationCodec.parse(""));
        assertNull(LocationCodec.parse("   "));
        assertNull(LocationCodec.parse("world;1;2;3"));
        assertNull(LocationCodec.parse("world;a;b;c;d;e"));
        assertNull(LocationCodec.parse(";1;2;3;4;5"));
    }

    @Test
    void encodingIsLocaleIndependent() {
        java.util.Locale old = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"));
            assertTrue(LocationCodec.encode("w", 1.5, 2, 3, 0, 0).contains("1.500"));
        } finally {
            java.util.Locale.setDefault(old);
        }
    }
}
