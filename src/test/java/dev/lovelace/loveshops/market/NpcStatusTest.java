package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.market.NpcStatus.Kind;
import dev.lovelace.loveshops.market.model.TradingMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NpcStatusTest {

    @Test
    void closedPointShowsClosedOrRobbed() {
        assertEquals(Kind.CLOSED, NpcStatus.pick(false, false, TradingMode.BOTH, true, true));
        assertEquals(Kind.ROBBED, NpcStatus.pick(false, true, TradingMode.BOTH, true, true));
    }

    @Test
    void noLotsInTheActiveModeIsNoLots() {
        assertEquals(Kind.EMPTY, NpcStatus.pick(true, false, TradingMode.SELL_ONLY, false, false));
        assertEquals(Kind.EMPTY, NpcStatus.pick(true, false, TradingMode.BUY_ONLY, false, false));
        assertEquals(Kind.EMPTY, NpcStatus.pick(true, false, TradingMode.BOTH, false, false));
    }

    @Test
    void lotsOfAnInactiveModeDoNotCount() {
        // only buy orders exist but the point sells only: nothing to show
        assertEquals(Kind.EMPTY, NpcStatus.pick(true, false, TradingMode.SELL_ONLY, false, true));
        assertEquals(Kind.EMPTY, NpcStatus.pick(true, false, TradingMode.BUY_ONLY, true, false));
    }

    @Test
    void activeModeWithLotsShowsItsStatus() {
        assertEquals(Kind.SELL_ONLY, NpcStatus.pick(true, false, TradingMode.SELL_ONLY, true, false));
        assertEquals(Kind.BUY_ONLY, NpcStatus.pick(true, false, TradingMode.BUY_ONLY, false, true));
        assertEquals(Kind.OPEN, NpcStatus.pick(true, false, TradingMode.BOTH, true, false));
        assertEquals(Kind.OPEN, NpcStatus.pick(true, false, TradingMode.BOTH, false, true));
    }
}
