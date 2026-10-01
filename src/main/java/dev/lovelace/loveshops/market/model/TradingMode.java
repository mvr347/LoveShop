package dev.lovelace.loveshops.market.model;

import java.util.Locale;

/**
 * Operating mode of a trade point: BOTH (both selling and buying),
 * SELL_ONLY (only selling goods from shelves), BUY_ONLY (only buying goods from players).
 */
public enum TradingMode {
    BOTH("Все операции"),
    SELL_ONLY("Только витрина"),
    BUY_ONLY("Только скупка");

    private final String displayName;

    TradingMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static TradingMode parse(String raw) {
        if (raw == null) return BOTH;
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BOTH;
        }
    }
}
