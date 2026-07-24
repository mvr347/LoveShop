package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.GuiUpdater;
import dev.lovelace.loveshops.models.BuyerItemData;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SellerManager {

    private final LoveShops plugin;
    private final CurrencyManager currencyManager;
    private boolean active = false;
    private Boolean forceActiveOverride = null;

    public SellerManager(LoveShops plugin, CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
    }

    public boolean isSellerActive() {
        if (forceActiveOverride != null) {
            return forceActiveOverride;
        }
        if (!plugin.getConfig().getBoolean("seller.enabled", true)) return false;
        String day = plugin.getConfig().getString("seller.arrival-day", "SUNDAY");
        String arrival = plugin.getConfig().getString("seller.arrival-time", "10:00");
        String departure = plugin.getConfig().getString("seller.departure-time", "18:00");

        return TimeUtils.isSellerTimeWindow(day, arrival, departure);
    }

    public void checkSellerStatus() {
        boolean nowActive = isSellerActive();
        if (nowActive != active) {
            this.active = nowActive;
            if (nowActive) {
                // Seller arrived (Flea market event active)
                for (String msg : plugin.getConfig().getStringList("seller.messages.arrival")) {
                    Bukkit.broadcast(MessageUtils.parse(msg));
                }
                // Despawn buyer NPCs during event
                for (var npc : plugin.getNpcManager().getNpcsByType("buyer")) {
                    plugin.getNpcManager().despawnNpcEntity(npc.uuid());
                }
                // Spawn seller NPCs
                for (var npc : plugin.getNpcManager().getNpcsByType("seller")) {
                    plugin.getNpcManager().spawnNpcEntity(npc);
                }
                // Spawn auctioneer NPCs (auction runs alongside the flea market)
                for (var npc : plugin.getNpcManager().getNpcsByType("auctioneer")) {
                    plugin.getNpcManager().spawnNpcEntity(npc);
                }
                // Move expensive buyer purchases into fresh auction lots, roll a new dynamic-pricing cycle
                plugin.getAuctionManager().createAuctionsFromPendingItems();
                plugin.getPriceCalculator().rollSellerPriceCycle();
            } else {
                // Seller departed (Flea market event ended)
                for (String msg : plugin.getConfig().getStringList("seller.messages.departure")) {
                    Bukkit.broadcast(MessageUtils.parse(msg));
                }
                // Despawn seller NPCs
                for (var npc : plugin.getNpcManager().getNpcsByType("seller")) {
                    plugin.getNpcManager().despawnNpcEntity(npc.uuid());
                }
                // Despawn auctioneer NPCs
                for (var npc : plugin.getNpcManager().getNpcsByType("auctioneer")) {
                    plugin.getNpcManager().despawnNpcEntity(npc.uuid());
                }
                // Respawn buyer NPCs
                for (var npc : plugin.getNpcManager().getNpcsByType("buyer")) {
                    plugin.getNpcManager().spawnNpcEntity(npc);
                }
            }
        }
    }

    public void forceStartSeller() {
        this.forceActiveOverride = true;
        this.active = false; // Reset local state so checkSellerStatus triggers state transition
        checkSellerStatus();
    }

    public void forceStopSeller() {
        this.forceActiveOverride = false;
        this.active = true; // Reset local state so checkSellerStatus triggers state transition
        checkSellerStatus();
    }

    public void resetSellerOverride() {
        this.forceActiveOverride = null;
        boolean oldState = this.active;
        this.active = !oldState; // Force re-evaluation
        checkSellerStatus();
    }

    public Boolean getForceActiveOverride() {
        return forceActiveOverride;
    }

    public CompletableFuture<List<BuyerItemData>> getAvailableItems() {
        CompletableFuture<List<BuyerItemData>> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<BuyerItemData> items = new ArrayList<>();
            String sql = "SELECT * FROM buyer_inventory WHERE sold_at IS NULL AND channel = 'seller' ORDER BY received_at DESC LIMIT 45";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    items.add(mapItem(rs));
                }
                future.complete(items);
            } catch (SQLException e) {
                plugin.getLogger().severe("Error fetching seller items: " + e.getMessage());
                future.complete(items);
            }
        });
        return future;
    }

    public CompletableFuture<Boolean> buyItem(Player player, int itemId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);

                // 1. Fetch item details
                String selectSql = "SELECT * FROM buyer_inventory WHERE id = ? AND sold_at IS NULL AND channel = 'seller'";
                BuyerItemData itemData = null;
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setInt(1, itemId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        itemData = mapItem(rs);
                    }
                }

                if (itemData == null) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                    future.complete(false);
                    return;
                }

                int finalPrice = plugin.getPriceCalculator().calculateSellPrice(conn, itemData);

                // Check player balance on main thread
                final BuyerItemData finalItemData = itemData;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (currencyManager.getPlayerBalance(player) < finalPrice) {
                        MessageUtils.sendMessage(player, plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
                        future.complete(false);
                        return;
                    }

                    // Async execution of update transaction
                    Bukkit.getAsyncScheduler().runNow(plugin, updateTask -> {
                        try {
                            String updateSql = "UPDATE buyer_inventory SET sold_at = strftime('%s', 'now') WHERE id = ? AND sold_at IS NULL";
                            try (PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {
                                psUpdate.setInt(1, itemId);
                                int updated = psUpdate.executeUpdate();

                                if (updated > 0) {
                                    plugin.getPriceCalculator().trackDemand(conn, finalItemData.itemType());
                                    conn.commit();
                                    conn.setAutoCommit(true);

                                    // Grant item on main thread
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        currencyManager.removeCurrency(player, finalPrice);
                                        ItemStack itemStack = ItemStackConverter.itemStackFromBase64(finalItemData.itemData());
                                        if (itemStack != null) {
                                            player.getInventory().addItem(itemStack);
                                        }
                                        MessageUtils.sendMessage(player, plugin.getLangManager().getRaw("gui.item-bought", "&aПокупка успешна!"));
                                        
                                        // Broadcast GUI update to all open viewers!
                                        GuiUpdater.broadcastSellerGuiUpdate(plugin, itemId);
                                        future.complete(true);
                                    });
                                } else {
                                    conn.rollback();
                                    conn.setAutoCommit(true);
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        MessageUtils.sendMessage(player, plugin.getConfig().getString("protection.item-already-sold-message", "&cЭтот предмет уже куплен другим игроком."));
                                        GuiUpdater.broadcastSellerGuiUpdate(plugin, itemId);
                                        future.complete(false);
                                    });
                                }
                            }
                        } catch (SQLException e) {
                            plugin.getLogger().severe("Error in seller purchase transaction: " + e.getMessage());
                            future.complete(false);
                        }
                    });
                });

            } catch (SQLException e) {
                plugin.getLogger().severe("Error preparing seller purchase: " + e.getMessage());
                future.complete(false);
            }
        });

        return future;
    }

    private BuyerItemData mapItem(ResultSet rs) throws SQLException {
        return new BuyerItemData(
            rs.getInt("id"),
            rs.getInt("npc_id"),
            UUID.fromString(rs.getString("player_uuid")),
            rs.getString("item_data"),
            rs.getInt("base_price"),
            rs.getInt("quantity"),
            rs.getLong("received_at"),
            rs.getObject("sold_at") != null ? rs.getLong("sold_at") : null,
            rs.getString("item_type"),
            rs.getString("channel")
        );
    }
}
