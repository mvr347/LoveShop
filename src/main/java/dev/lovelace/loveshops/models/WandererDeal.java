package dev.lovelace.loveshops.models;

import java.util.List;
import java.util.UUID;

public record WandererDeal(
    int id,
    UUID playerUuid,
    String status,
    long orderedAt,
    long readyAt,
    long expiresAt,
    List<WandererDealItem> items
) {
    public boolean isReady() {
        return (System.currentTimeMillis() / 1000) >= readyAt;
    }

    public boolean isExpired() {
        return (System.currentTimeMillis() / 1000) >= expiresAt;
    }

    public long remainingSeconds() {
        long now = System.currentTimeMillis() / 1000;
        return Math.max(0, readyAt - now);
    }

    public boolean hasUnboughtItems() {
        if (items == null || items.isEmpty()) return false;
        return items.stream().anyMatch(i -> !i.bought());
    }

    public WandererDeal withItems(List<WandererDealItem> newItems) {
        return new WandererDeal(id, playerUuid, status, orderedAt, readyAt, expiresAt, newItems);
    }

    public WandererDeal withStatus(String newStatus) {
        return new WandererDeal(id, playerUuid, newStatus, orderedAt, readyAt, expiresAt, items);
    }
}
