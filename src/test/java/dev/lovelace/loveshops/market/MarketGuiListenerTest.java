package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketGuiListenerTest {

    @Test
    void dragFromInventoryKeepsOnlyMenuSlotsInOrder() {
        // raw slots 31 and 40 belong to the player's inventory of a 27-slot menu
        assertEquals(List.of(11, 13, 15), MarketGuiListener.dragTargets(Set.of(40, 15, 11, 31, 13), 27));
    }

    @Test
    void dragOnlyInsideInventoryHasNoTargets() {
        assertEquals(List.of(), MarketGuiListener.dragTargets(Set.of(30, 31), 27));
        assertEquals(List.of(), MarketGuiListener.dragTargets(Set.of(), 27));
    }

    @Test
    void singleSlotDragStillWorks() {
        assertEquals(List.of(13), MarketGuiListener.dragTargets(Set.of(13, 28), 27));
    }
}
