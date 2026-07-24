package dev.lovelace.loveshops.models;

import java.util.UUID;

public record BuyerItemData(
    int id,
    int npcId,
    UUID playerUuid,
    String itemData,
    int basePrice,
    int quantity,
    long receivedAt,
    Long soldAt
) {
    public boolean isSold() {
        return soldAt != null && soldAt > 0;
    }
}
