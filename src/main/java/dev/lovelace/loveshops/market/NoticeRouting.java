package dev.lovelace.loveshops.market;

import java.util.Set;

/**
 * Decides how a market text ({@code market.<key>} in lang.yml) reaches a player.
 *
 * <p>Event-style notices go through LoveNotify (action bar by default, chat fallback decided by LoveCore);
 * {@link #IMPORTANT} ones are always duplicated to chat. Everything that is not listed stays a plain chat
 * message: chat prompts, admin wizard steps, NPC speech lines and any key added later, so a forgotten key
 * can never silently change behaviour.</p>
 */
public final class NoticeRouting {

    public enum Kind { CHAT, EVENT, IMPORTANT }

    /** Money moved, a stall changed hands or was lost: the player must be able to scroll back to it. */
    private static final Set<String> IMPORTANT = Set.of(
            "trade-bought", "trade-sold", "notice-bought", "notice-sold",
            "till-collected", "till-deposited", "upgrade-done", "guard-hired", "guard-expired",
            "feudal-rented", "feudal-extended-days", "feudal-returned",
            "stall-rented", "rent-warning", "rent-grace-closed", "shop-closed-reputation",
            "shop-robbed", "robbery-success", "returns-delivered", "give-failed", "transfer-received");

    /** Short statuses of a click or of something that happened to the player's stall. */
    private static final Set<String> EVENT = Set.of(
            "listing-added", "listing-removed", "listing-stock-added", "listing-price-changed", "listing-error",
            "listing-no-slot", "listing-forbidden", "listing-is-coin", "listing-price-low", "listing-price-high",
            "listing-slot-taken", "slot-locked-hint",
            "discount-given", "discount-removed",
            "blacklist-added", "blacklist-removed", "blacklist-full", "blacklist-self",
            "shop-opened", "shop-closed", "shop-closed-admin", "shop-reopened-reputation", "rent-grace-reopened",
            "not-owner", "economy-down", "db-error",
            "till-empty", "till-no-space", "till-need-coins",
            "trade-self", "trade-gone", "trade-not-enough-stock", "trade-no-money", "trade-no-space",
            "trade-till-empty", "trade-order-full", "trade-no-items", "trade-mode-sell-off", "trade-mode-buy-off",
            "upgrade-max", "upgrade-no-money",
            "guard-fired", "guard-no-money", "guard-disabled",
            "transfer-done", "transfer-failed", "transfer-self",
            "buy-collected", "buy-collected-all", "buy-nothing-collected",
            "storage-full", "storage-deposited", "storage-empty", "storage-collected", "storage-inventory-full",
            "feudal-taken", "feudal-no-funds", "feudal-bad-weeks", "feudal-prepaid-max", "feudal-no-point-owned",
            "returns-left");

    private NoticeRouting() {}

    public static Kind classify(String key) {
        if (key == null) return Kind.CHAT;
        if (IMPORTANT.contains(key)) return Kind.IMPORTANT;
        if (EVENT.contains(key)) return Kind.EVENT;
        return Kind.CHAT;
    }
}
