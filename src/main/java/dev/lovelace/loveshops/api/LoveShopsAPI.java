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
}
