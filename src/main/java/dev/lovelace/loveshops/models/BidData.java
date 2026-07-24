package dev.lovelace.loveshops.models;

import java.util.UUID;

public record BidData(
    int id,
    int auctionId,
    UUID bidderUuid,
    int bidAmount,
    long placedAt
) {}
