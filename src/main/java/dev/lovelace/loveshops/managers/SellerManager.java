package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
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
    private boolean active = false;
    private Boolean forceActiveOverride = null;

    public SellerManager(LoveShops plugin) {
        this.plugin = plugin;
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
        // ScheduleListener drives this from Bukkit.getAsyncScheduler(), not the main thread -
        // hop over before touching Bukkit.broadcast()/NPC spawn-despawn below, same self-guard
        // idiom NpcManager.spawnNpcEntity/despawnNpcEntity already use.
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::checkSellerStatus);
            return;
        }
        boolean nowActive = isSellerActive();
        if (nowActive == active) {
            return;
        }
        this.active = nowActive;

        if (nowActive) {
            handleArrival();
        } else {
            handleDeparture();
        }
    }

    /**
     * Flea market's arrival edge. The auction/price-cycle plumbing (rollSellerPriceCycle,
     * createAuctionsFromPendingItems) runs unconditionally here - it's a separate system
     * (rare/expensive items routed to the "auction" channel by BuyerManager.processSale) with
     * its own stock independent of the ordinary seller channel. The auctioneer NPC itself,
     * though, is NOT spawned here anymore (2026-09-26, owner request: auctioneer should hide
     * while inactive) - it's gated purely on real active auctions via checkAuctioneerVisibility(),
     * called every 30s from ScheduleListener alongside this method, so it reacts both to new
     * auctions created here and to auctions completing mid-window (checkAndCompleteAuctions).
     * The seller NPC + arrival broadcast are gated on there being anything to sell, and only for
     * the natural, time-based transition: an admin using /loveshopsadmin seller start
     * (forceStartSeller) is explicitly asking to see the market open regardless, e.g. for
     * testing, so that path is never silently skipped.
     */
    private void handleArrival() {
        plugin.getPriceCalculator().rollSellerPriceCycle();

        if (forceActiveOverride != null) {
            plugin.getAuctionManager().createAuctionsFromPendingItems();
            performSellerArrival();
            return;
        }

        // createAuctionsFromPendingItems() only moves channel='auction' rows today, so it can't
        // actually change the channel='seller' count checked below - but we still wait for it
        // to finish before counting, so "should the flea market open" always reflects the same
        // post-routing state the Seller GUI itself would show, even if that routing ever grows
        // to touch the seller channel too.
        plugin.getAuctionManager().createAuctionsFromPendingItems().thenRun(() ->
            getAvailableItemCount().thenAccept(count -> Bukkit.getScheduler().runTask(plugin, () -> {
                int minItems = Math.max(1, plugin.getConfig().getInt("seller.min-items-to-spawn", 1));
                if (count >= minItems) {
                    performSellerArrival();
                } else {
                    plugin.getLogger().info("Барахолка: пропускаем прибытие торговца — доступно только "
                        + count + " предмет(ов) из " + minItems + " необходимых (channel='seller').");
                }
            }))
        );
    }

    private void performSellerArrival() {
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
    }

    private void handleDeparture() {
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
        // Whatever wasn't bought stays unsold forever (no buyer NPC is up to sell it again)
        // and must not roll over into next week's flea market alongside fresh stock.
        clearUnsoldStock();
    }

    /**
     * Аукционер должен быть виден, только пока есть реальные активные лоты (2026-09-26, просьба
     * владельца) - раньше NPC стоял весь период расписания Барахолки (seller.arrival-time..
     * departure-time), даже без единого активного аукциона. Дергается каждые 30 секунд из
     * ScheduleListener, после AuctionManager#checkAndCompleteAuctions - лот, завершившийся в
     * этом же тике, обычно уже не попадёт в getActiveAuctions() ниже; в редком случае гонки
     * (completeAuction коммитит асинхронно) аукционер спрячется на следующем 30-секундном
     * проходе, что для NPC на глаз не критично. Вне окна Барахолки ничего не делает:
     * handleDeparture() уже despawn'ит аукционера на границе ухода, а handleArrival() создаёт
     * новые аукционы ДО первого вызова этого метода в новом окне.
     */
    public void checkAuctioneerVisibility() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::checkAuctioneerVisibility);
            return;
        }
        if (!isSellerActive()) {
            return;
        }
        plugin.getAuctionManager().getActiveAuctions().thenAccept(auctions ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                boolean hasActiveLots = !auctions.isEmpty();
                for (var npc : plugin.getNpcManager().getNpcsByType("auctioneer")) {
                    if (hasActiveLots) {
                        plugin.getNpcManager().spawnNpcEntity(npc);
                    } else {
                        plugin.getNpcManager().despawnNpcEntity(npc.uuid());
                    }
                }
            })
        );
    }

    /**
     * Deletes seller-channel rows nobody bought before the event closed. Async, off the main
     * thread, matching every other DB access in this class.
     */
    private void clearUnsoldStock() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = "DELETE FROM buyer_inventory WHERE channel = 'seller' AND sold_at IS NULL";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("Барахолка: очищено " + deleted + " непроданных предметов после закрытия события.");
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error clearing unsold seller stock: " + e.getMessage());
            }
        });
    }

    /** Count of items currently purchasable in the Seller GUI (channel='seller', unsold). */
    public CompletableFuture<Integer> getAvailableItemCount() {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = "SELECT COUNT(*) AS cnt FROM buyer_inventory WHERE sold_at IS NULL AND channel = 'seller'";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();
                future.complete(rs.next() ? rs.getInt("cnt") : 0);
            } catch (SQLException e) {
                plugin.getLogger().severe("Error counting seller items: " + e.getMessage());
                future.complete(0);
            }
        });
        return future;
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
            BuyerItemData itemData = null;
            int finalPrice = 0;

            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                String selectSql = "SELECT * FROM buyer_inventory WHERE id = ? AND sold_at IS NULL AND channel = 'seller'";
                try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                    ps.setInt(1, itemId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        itemData = mapItem(rs);
                    }
                }

                if (itemData != null) {
                    finalPrice = plugin.getPriceCalculator().calculateSellPrice(conn, itemData);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error preparing seller purchase: " + e.getMessage());
                future.complete(false);
                return;
            }

            if (itemData == null) {
                future.complete(false);
                return;
            }

            final BuyerItemData finalItemData = itemData;
            final ItemStack itemStack = ItemStackConverter.itemStackFromBase64(finalItemData.itemData());
            final int basePrice = finalPrice;

            LoveEconomy economy = plugin.getEconomy().orElse(null);

            // Main thread check: balance
            Bukkit.getScheduler().runTask(plugin, () -> {
                final long behaviorTaxedPrice = dev.lovelace.lovecore.api.LoveCore.service(dev.lovelace.lovecore.api.economy.TaxOracle.class)
                        .map(tax -> tax.applyToCost(player.getUniqueId(), basePrice))
                        .orElse((long) basePrice);
                // merchant-tax (config.yml): flat economy-sink markup on top of the behavioral
                // TaxOracle rate above — see PriceCalculator#applyMerchantTaxToCost. Wanderer
                // never goes through this.
                final long itemPrice = plugin.getPriceCalculator().applyMerchantTaxToCost(behaviorTaxedPrice);
                if (economy == null || !economy.has(player, itemPrice)) {
                    MessageUtils.sendMessage(player, MessageUtils.currencyIcon() + plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
                    future.complete(false);
                    return;
                }

                // Async execution of update transaction
                Bukkit.getAsyncScheduler().runNow(plugin, updateTask -> {
                    try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                        conn.setAutoCommit(false);
                        String updateSql = "UPDATE buyer_inventory SET sold_at = strftime('%s', 'now') WHERE id = ? AND sold_at IS NULL";
                        int updated = 0;

                        try (PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {
                            psUpdate.setInt(1, itemId);
                            updated = psUpdate.executeUpdate();
                        }

                        if (updated > 0) {
                            plugin.getPriceCalculator().trackDemand(conn, finalItemData.itemType());
                            conn.commit();

                            // Grant item on main thread
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                // charge() can fail here even though has() passed on the earlier
                                // check above (physical-coin economy, balance can change during
                                // the async round-trip we just made). Ignoring that used to hand
                                // the item out for free; now a failed charge un-sells the row so
                                // the item isn't lost and isn't given away unpaid.
                                if (!economy.charge(player, itemPrice)) {
                                    MessageUtils.sendMessage(player, MessageUtils.currencyIcon() + plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
                                    Bukkit.getAsyncScheduler().runNow(plugin, revertTask -> {
                                        try (Connection revertConn = plugin.getDatabaseManager().getConnection();
                                             PreparedStatement psRevert = revertConn.prepareStatement(
                                                 "UPDATE buyer_inventory SET sold_at = NULL WHERE id = ?")) {
                                            psRevert.setInt(1, itemId);
                                            psRevert.executeUpdate();
                                        } catch (SQLException e) {
                                            plugin.getLogger().severe("Error reverting failed seller purchase #" + itemId + ": " + e.getMessage());
                                        }
                                        GuiUpdater.broadcastSellerGuiUpdate(plugin, itemId);
                                    });
                                    future.complete(false);
                                    return;
                                }
                                if (itemStack != null) {
                                    for (ItemStack extra : player.getInventory().addItem(itemStack).values()) {
                                        player.getWorld().dropItemNaturally(player.getLocation(), extra);
                                    }
                                }
                                MessageUtils.sendMessage(player, plugin.getLangManager().getRaw("gui.item-bought", "&aПокупка успешна!"));

                                // Broadcast GUI update to all open viewers!
                                GuiUpdater.broadcastSellerGuiUpdate(plugin, itemId);
                                future.complete(true);
                            });
                        } else {
                            conn.rollback();
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                MessageUtils.sendMessage(player, plugin.getConfig().getString("protection.item-already-sold-message", "&cЭтот предмет уже куплен другим игроком."));
                                GuiUpdater.broadcastSellerGuiUpdate(plugin, itemId);
                                future.complete(false);
                            });
                        }
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Error in seller purchase transaction: " + e.getMessage());
                        future.complete(false);
                    }
                });
            });
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
