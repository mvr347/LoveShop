package dev.lovelace.loveshops.models;

import java.util.UUID;

public record AuctionData(
    int id,
    int auctioneerNpcId,
    String itemData,
    int startingPrice,
    int currentHighestBid,
    UUID highestBidderUuid,
    long startsAt,
    long endsAt,
    String status,
    UUID winnerUuid,
    Long completedAt,
    int buyoutPrice
) {
    public boolean isActive() {
        return "active".equalsIgnoreCase(status) && System.currentTimeMillis() / 1000 < endsAt;
    }
}
