package dev.lovelace.loveshops.managers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EventNpcPlanTest {

    @Test
    void eventStartCreatesAndEndDestroys() {
        assertEquals(NpcManager.EphemeralAction.CREATE, NpcManager.decide(true, false));
        assertEquals(NpcManager.EphemeralAction.DESTROY, NpcManager.decide(false, true));
    }

    @Test
    void nothingToDoWhenStateMatches() {
        assertEquals(NpcManager.EphemeralAction.NONE, NpcManager.decide(true, true));
        assertEquals(NpcManager.EphemeralAction.NONE, NpcManager.decide(false, false));
    }

    @Test
    void untrackedTaggedNpcsAreOrphans() {
        Set<Integer> tracked = Set.of(2, 5);
        assertEquals(List.of(1, 3), NpcManager.orphans(List.of(1, 2, 3, 5), tracked::contains));
        assertTrue(NpcManager.orphans(List.<Integer>of(), tracked::contains).isEmpty());
    }

    @Test
    void onlyEventTypesAreEphemeral() {
        assertTrue(NpcManager.isEphemeralType("caravaner"));
        assertTrue(NpcManager.isEphemeralType("LostCaravan"));
        assertTrue(NpcManager.isEphemeralType("wanderer"));
        assertFalse(NpcManager.isEphemeralType("banker"));
        assertFalse(NpcManager.isEphemeralType("commissioner"));
        assertFalse(NpcManager.isEphemeralType(null));
    }
}
