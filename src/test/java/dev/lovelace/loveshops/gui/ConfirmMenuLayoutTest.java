package dev.lovelace.loveshops.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveshops.utils.ConfirmMenuLayout;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConfirmMenuLayoutTest {
    @Test
    void zonesAreTwoDisjointThreeByThreeBlocksInsideTwentySevenSlots() {
        Set<Integer> all = new HashSet<>();
        for (int s : ConfirmMenuLayout.CONFIRM_ZONE) assertTrue(all.add(s) && s >= 0 && s < 27);
        for (int s : ConfirmMenuLayout.CANCEL_ZONE) assertTrue(all.add(s) && s >= 0 && s < 27);
        assertEquals(18, all.size());
        assertEquals(Set.of(1, 2, 3, 10, 11, 12, 19, 20, 21), Set.copyOf(java.util.Arrays.stream(ConfirmMenuLayout.CONFIRM_ZONE).boxed().toList()));
        assertEquals(Set.of(5, 6, 7, 14, 15, 16, 23, 24, 25), Set.copyOf(java.util.Arrays.stream(ConfirmMenuLayout.CANCEL_ZONE).boxed().toList()));
    }

    @Test
    void slotsClassifyAsConfirmCancelOrNeither() {
        assertTrue(ConfirmMenuLayout.isConfirm(11));
        assertTrue(ConfirmMenuLayout.isCancel(15));
        assertFalse(ConfirmMenuLayout.isConfirm(4));
        assertFalse(ConfirmMenuLayout.isCancel(4));
        assertFalse(ConfirmMenuLayout.isConfirm(0));
    }
}
