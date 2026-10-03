package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.market.model.TradingMode;

/** Which status line a trader shows above their head; a pure function so it can be tested without a server. */
public final class NpcStatus {

    public enum Kind { ROBBED, CLOSED, EMPTY, SELL_ONLY, BUY_ONLY, OPEN }

    private NpcStatus() {}

    /**
     * @param hasSell at least one sell lot with goods in stock
     * @param hasBuy  at least one buy order that still wants goods
     */
    public static Kind pick(boolean open, boolean robbed, TradingMode mode, boolean hasSell, boolean hasBuy) {
        if (!open) return robbed ? Kind.ROBBED : Kind.CLOSED;
        boolean sellActive = mode != TradingMode.BUY_ONLY;
        boolean buyActive = mode != TradingMode.SELL_ONLY;
        // A mode that is switched on but has no lots at all is "no lots", not "open".
        if (!((sellActive && hasSell) || (buyActive && hasBuy))) return Kind.EMPTY;
        return switch (mode) {
            case SELL_ONLY -> Kind.SELL_ONLY;
            case BUY_ONLY -> Kind.BUY_ONLY;
            case BOTH -> Kind.OPEN;
        };
    }
}
