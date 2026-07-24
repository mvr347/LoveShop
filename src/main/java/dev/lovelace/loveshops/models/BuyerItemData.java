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
    Long soldAt,
    String itemType,
    String channel
) {
    public boolean isSold() {
        return soldAt != null && soldAt > 0;
    }
}
