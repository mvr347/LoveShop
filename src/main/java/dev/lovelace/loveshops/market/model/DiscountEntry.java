package dev.lovelace.loveshops.market.model;

import java.util.UUID;

public record DiscountEntry(UUID pointId, UUID beneficiaryUuid, int percent, long expiresAt) {
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }
}
