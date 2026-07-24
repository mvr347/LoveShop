package dev.lovelace.loveshops.events;

import dev.lovelace.loveshops.models.AuctionData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class AuctionBidPlacedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player bidder;
    private final AuctionData auction;
    private final int bidAmount;

    public AuctionBidPlacedEvent(Player bidder, AuctionData auction, int bidAmount) {
        this.bidder = bidder;
        this.auction = auction;
        this.bidAmount = bidAmount;
    }

    public Player getBidder() { return bidder; }
    public AuctionData getAuction() { return auction; }
    public int getBidAmount() { return bidAmount; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
