package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.models.WandererDeal;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory view of online players' Wanderer deals for PlaceholderAPI. onRequest runs on the main
 * thread and must never wait on the DB, so it reads this cache; it is filled on join and after
 * each deal change. Only tracked (online) players are stored, so it cannot grow without bound.
 */
public final class WandererDealCache {

    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, WandererDeal> deals = new ConcurrentHashMap<>();

    public void track(UUID uuid) {
        tracked.add(uuid);
    }

    public void untrack(UUID uuid) {
        tracked.remove(uuid);
        deals.remove(uuid);
    }

    public boolean isTracked(UUID uuid) {
        return tracked.contains(uuid);
    }

    /** Stores the freshly loaded deal, ignoring players who left in the meantime. */
    public void update(UUID uuid, Optional<WandererDeal> deal) {
        if (!tracked.contains(uuid)) return;
        if (deal.isPresent()) deals.put(uuid, deal.get());
        else deals.remove(uuid);
    }

    public Optional<WandererDeal> get(UUID uuid) {
        return Optional.ofNullable(deals.get(uuid));
    }

    public String status(UUID uuid, long nowSeconds) {
        WandererDeal deal = deals.get(uuid);
        return deal == null ? "NONE" : effectiveStatus(deal, nowSeconds);
    }

    /** Same promotion rules getPlayerDeal applies when it reads the row. */
    public static String effectiveStatus(WandererDeal deal, long nowSeconds) {
        String status = deal.status();
        if (deal.expiresAt() > 0 && nowSeconds > deal.expiresAt() && !"COMPLETED".equalsIgnoreCase(status)) {
            return "EXPIRED";
        }
        if ("WAITING".equalsIgnoreCase(status) && nowSeconds >= deal.readyAt()) {
            return "READY";
        }
        return status;
    }
}
