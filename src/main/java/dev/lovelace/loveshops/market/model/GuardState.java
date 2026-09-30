package dev.lovelace.loveshops.market.model;

public enum GuardState {
    NONE, ACTIVE, UNPAID;

    public static GuardState parse(String raw) {
        if (raw == null) return NONE;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
