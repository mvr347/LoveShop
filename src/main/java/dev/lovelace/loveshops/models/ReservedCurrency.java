package dev.lovelace.loveshops.models;

import java.util.UUID;

public record ReservedCurrency(
    int id,
    UUID playerUuid,
    int reservedAmount,
    Integer lastBidAuctionId
) {}
