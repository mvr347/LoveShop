package dev.lovelace.loveshops.market.model;

import java.util.UUID;

public record BlacklistEntry(UUID pointId, UUID playerUuid, String reason, long createdAt) {
}
