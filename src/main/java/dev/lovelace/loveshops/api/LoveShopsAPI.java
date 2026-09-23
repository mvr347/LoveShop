package dev.lovelace.loveshops.api;

import dev.lovelace.loveshops.models.AuctionData;
import dev.lovelace.loveshops.models.BuyerItemData;
import dev.lovelace.loveshops.models.NpcData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface LoveShopsAPI {

    CompletableFuture<String> getBuyerStatus(UUID playerUuid);

    CompletableFuture<Void> setBuyerStatus(UUID playerUuid, String status, String setBy, String customMessage);

    int calculateBuyPrice(Player player, ItemStack item);

    CompletableFuture<Boolean> processBuyerSale(Player player, ItemStack item);

    CompletableFuture<List<BuyerItemData>> getSellerItems();

    CompletableFuture<Boolean> buySellerItem(Player player, int itemId);

    CompletableFuture<List<AuctionData>> getActiveAuctions();

    CompletableFuture<Boolean> placeAuctionBid(Player bidder, int auctionId, int bidAmount);

    List<NpcData> getAllNpcs();

    /**
     * Creates an auction lot for an item supplied by another plugin (e.g. LoveBrew routing a
     * top-quality, long-aged beverage to the Auctioneer instead of an instant NPC sale). The
     * item is not taken from any inventory — the caller is responsible for removing it from
     * wherever it came from before/after invoking this.
     * @param startingPrice opening bid, typically the caller's own formula price
     * @return a future completing with the new auction's id
     */
    CompletableFuture<Integer> createExternalAuction(ItemStack item, int startingPrice);
}
