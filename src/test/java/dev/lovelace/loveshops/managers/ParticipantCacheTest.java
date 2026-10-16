package dev.lovelace.loveshops.managers;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ParticipantCacheTest {
    @Test
    void loadsOncePerSessionAndTracksRegistrations() {
        ParticipantCache cache = new ParticipantCache();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        assertTrue(cache.contains(1, a, id -> { loads.incrementAndGet(); return Set.of(a); }));
        assertFalse(cache.contains(1, b, id -> { loads.incrementAndGet(); return Set.of(); }));
        cache.add(1, b);
        assertTrue(cache.contains(1, b, id -> Set.of()));
        assertEquals(1, loads.get());
    }

    @Test
    void retainOnlyDropsOldSessions() {
        ParticipantCache cache = new ParticipantCache();
        UUID a = UUID.randomUUID();
        cache.contains(1, a, id -> Set.of(a));
        cache.contains(2, a, id -> Set.of());
        cache.retainOnly(2);
        assertTrue(cache.contains(1, a, id -> Set.of(a)));
        assertFalse(cache.contains(2, a, id -> Set.of(a)));
    }
}
