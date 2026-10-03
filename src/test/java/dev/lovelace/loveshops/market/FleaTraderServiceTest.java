package dev.lovelace.loveshops.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The flea trader is a stub: it must stay inert, whatever the switch says. */
class FleaTraderServiceTest {

    @Test
    void disabledTraderSpawnsAndOpensNothing() {
        FleaTraderService service = new FleaTraderService(() -> false);
        assertFalse(service.isEnabled());
        assertFalse(service.spawn(null));
        assertFalse(service.open(null));
        assertEquals(0, service.despawn());
    }

    @Test
    void enabledSwitchStillSpawnsNothingUntilLogicIsWritten() {
        FleaTraderService service = new FleaTraderService(() -> true);
        assertTrue(service.isEnabled());
        assertFalse(service.spawn(null));
        assertFalse(service.open(null));
    }
}
