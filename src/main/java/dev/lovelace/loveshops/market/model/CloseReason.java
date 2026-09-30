package dev.lovelace.loveshops.market.model;

/** Why a trade point is closed. Decides who may open it again. */
public enum CloseReason {
    /** The owner closed it and may open it any time. */
    OWNER(true),
    /** Robbed: the owner has to open the shop again by hand. */
    ROBBERY(true),
    /** Rent is overdue (grace period): opens by itself once the rent is paid. */
    RENT_GRACE(false),
    /** The owner's reputation keeps them off the market: opens by itself once it recovers. */
    REPUTATION(false),
    /** Closed by an administrator. */
    ADMIN(false);

    private final boolean ownerMayReopen;

    CloseReason(boolean ownerMayReopen) {
        this.ownerMayReopen = ownerMayReopen;
    }

    public boolean ownerMayReopen() {
        return ownerMayReopen;
    }

    public static CloseReason parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
