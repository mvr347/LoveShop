package dev.lovelace.loveshops.api;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.AuctionData;
import dev.lovelace.loveshops.models.BuyerItemData;
import dev.lovelace.loveshops.models.NpcData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class LoveShopsAPIImpl implements LoveShopsAPI {

    private final LoveShops plugin;

    public LoveShopsAPIImpl(LoveShops plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<String> getBuyerStatus(UUID playerUuid) {
        return CompletableFuture.completedFuture("default");
    }

    @Override
    public CompletableFuture<Void> setBuyerStatus(UUID playerUuid, String status, String setBy, String customMessage) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public int calculateBuyPrice(Player player, ItemStack item) {
        return 0;
    }

    @Override
    public CompletableFuture<Boolean> processBuyerSale(Player player, ItemStack item) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<List<BuyerItemData>> getSellerItems() {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Boolean> buySellerItem(Player player, int itemId) {
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public CompletableFuture<List<AuctionData>> getActiveAuctions() {
        return plugin.getAuctionManager().getActiveAuctions();
    }

    @Override
    public CompletableFuture<Boolean> placeAuctionBid(Player bidder, int auctionId, int bidAmount) {
        return plugin.getAuctionManager().placeBid(bidder, auctionId, bidAmount);
    }

    @Override
    public List<NpcData> getAllNpcs() {
        return new ArrayList<>(plugin.getNpcManager().getAllNpcs());
    }

    @Override
    public CompletableFuture<Integer> createExternalAuction(ItemStack item, int startingPrice) {
        return plugin.getAuctionManager().createAuction(item, startingPrice);
    }
}
