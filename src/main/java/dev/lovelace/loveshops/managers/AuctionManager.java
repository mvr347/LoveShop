package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.events.AuctionBidPlacedEvent;
import dev.lovelace.loveshops.models.AuctionData;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
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

public class AuctionManager {

    private final LoveShops plugin;
    private final CurrencyManager currencyManager;

    public AuctionManager(LoveShops plugin, CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.currencyManager = currencyManager;
    }

    public CompletableFuture<Integer> createAuction(ItemStack item, int startingPrice) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        String base64Item = ItemStackConverter.itemStackToBase64(item);

        List<NpcData> npcs = plugin.getNpcManager().getNpcsByType("auctioneer");
        Integer npcId = npcs.isEmpty() ? null : npcs.get(0).id();

        long now = System.currentTimeMillis() / 1000;
        int hours = plugin.getConfig().getInt("auctioneer.auction-duration-hours", 24);
        long endsAt = now + (hours * 3600L);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO auctions (auctioneer_npc_id, item_data, starting_price, current_highest_bid, starts_at, ends_at, status)
                VALUES (?, ?, ?, ?, ?, ?, 'active')
            """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                if (npcId != null) {
                    ps.setInt(1, npcId);
                } else {
                    ps.setNull(1, java.sql.Types.INTEGER);
                }
                ps.setString(2, base64Item);
                ps.setInt(3, startingPrice);
                ps.setInt(4, startingPrice);
                ps.setLong(5, now);
                ps.setLong(6, endsAt);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                int auctionId = rs.next() ? rs.getInt(1) : 0;
                future.complete(auctionId);
            } catch (SQLException e) {
                plugin.getLogger().severe("Error creating auction: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public CompletableFuture<List<AuctionData>> getActiveAuctions() {
        CompletableFuture<List<AuctionData>> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<AuctionData> auctions = new ArrayList<>();
            String sql = "SELECT * FROM auctions WHERE status = 'active' ORDER BY ends_at ASC";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    auctions.add(mapAuction(rs));
                }
                future.complete(auctions);
            } catch (SQLException e) {
                plugin.getLogger().severe("Error fetching active auctions: " + e.getMessage());
                future.complete(auctions);
            }
        });
        return future;
    }

    public int getMinimumNextBid(AuctionData auction) {
        String stepType = plugin.getConfig().getString("auctioneer.bid-step.type", "percentage");
        int stepVal = plugin.getConfig().getInt("auctioneer.bid-step.value", 5);

        int current = auction.currentHighestBid();
        if ("percentage".equalsIgnoreCase(stepType)) {
            int step = (int) Math.ceil(current * (stepVal / 100.0));
            return current + Math.max(1, step);
        } else {
            return current + stepVal;
        }
    }

    public CompletableFuture<Boolean> placeBid(Player bidder, int auctionId, int bidAmount) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);

                // Fetch auction details
                String selSql = "SELECT * FROM auctions WHERE id = ? AND status = 'active'";
                AuctionData auction = null;
                try (PreparedStatement ps = conn.prepareStatement(selSql)) {
                    ps.setInt(1, auctionId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        auction = mapAuction(rs);
                    }
                }

                if (auction == null) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                    future.complete(false);
                    return;
                }

                int minNextBid = getMinimumNextBid(auction);
                if (bidAmount < minNextBid) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        MessageUtils.sendMessage(bidder, "&cСтавка слишком мала! Минимальная ставка: " + minNextBid);
                    });
                    future.complete(false);
                    return;
                }

                // Check bidder balance on main thread
                final AuctionData finalAuction = auction;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (currencyManager.getPlayerBalance(bidder) < bidAmount) {
                        MessageUtils.sendMessage(bidder, "&cНедостаточно средств на балансе!");
                        future.complete(false);
                        return;
                    }

                    // Perform database updates async
                    Bukkit.getAsyncScheduler().runNow(plugin, bidTask -> {
                        try {
                            // 1. Unreserve previous highest bidder if exists
                            if (finalAuction.highestBidderUuid() != null) {
                                String unresSql = "UPDATE reserved_currency SET reserved_amount = 0 WHERE player_uuid = ?";
                                try (PreparedStatement psUnres = conn.prepareStatement(unresSql)) {
                                    psUnres.setString(1, finalAuction.highestBidderUuid().toString());
                                    psUnres.executeUpdate();
                                }
                            }

                            // 2. Reserve currency for new bidder
                            String resSql = """
                                INSERT INTO reserved_currency (player_uuid, reserved_amount, last_bid_auction_id)
                                VALUES (?, ?, ?)
                                ON CONFLICT(player_uuid) DO UPDATE SET reserved_amount = EXCLUDED.reserved_amount, last_bid_auction_id = EXCLUDED.last_bid_auction_id
                            """;
                            try (PreparedStatement psRes = conn.prepareStatement(resSql)) {
                                psRes.setString(1, bidder.getUniqueId().toString());
                                psRes.setInt(2, bidAmount);
                                psRes.setInt(3, auctionId);
                                psRes.executeUpdate();
                            }

                            // 3. Update auction record
                            String upAucSql = "UPDATE auctions SET current_highest_bid = ?, highest_bidder_uuid = ? WHERE id = ?";
                            try (PreparedStatement psUpAuc = conn.prepareStatement(upAucSql)) {
                                psUpAuc.setInt(1, bidAmount);
                                psUpAuc.setString(2, bidder.getUniqueId().toString());
                                psUpAuc.setInt(3, auctionId);
                                psUpAuc.executeUpdate();
                            }

                            // 4. Log bid history
                            String insBidSql = "INSERT INTO auction_bids (auction_id, bidder_uuid, bid_amount) VALUES (?, ?, ?)";
                            try (PreparedStatement psBid = conn.prepareStatement(insBidSql)) {
                                psBid.setInt(1, auctionId);
                                psBid.setString(2, bidder.getUniqueId().toString());
                                psBid.setInt(3, bidAmount);
                                psBid.executeUpdate();
                            }

                            conn.commit();
                            conn.setAutoCommit(true);

                            Bukkit.getScheduler().runTask(plugin, () -> {
                                MessageUtils.sendMessage(bidder, "&a✓ Ставка принята: " + bidAmount + " монет!");
                                Bukkit.getPluginManager().callEvent(new AuctionBidPlacedEvent(bidder, finalAuction, bidAmount));
                                future.complete(true);
                            });

                        } catch (SQLException e) {
                            plugin.getLogger().severe("Error placing auction bid: " + e.getMessage());
                            future.complete(false);
                        }
                    });
                });

            } catch (SQLException e) {
                plugin.getLogger().severe("Error initiating bid transaction: " + e.getMessage());
                future.complete(false);
            }
        });

        return future;
    }

    public void checkAndCompleteAuctions() {
        long now = System.currentTimeMillis() / 1000;
        String sql = "SELECT * FROM auctions WHERE status = 'active' AND ends_at <= ?";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, now);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                AuctionData auction = mapAuction(rs);
                completeAuction(auction);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking auctions: " + e.getMessage());
        }
    }

    private void completeAuction(AuctionData auction) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                conn.setAutoCommit(false);

                String upSql = "UPDATE auctions SET status = 'completed', winner_uuid = ?, completed_at = strftime('%s', 'now') WHERE id = ?";
                try (PreparedStatement ps = conn.prepareStatement(upSql)) {
                    ps.setString(1, auction.highestBidderUuid() != null ? auction.highestBidderUuid().toString() : null);
                    ps.setInt(2, auction.id());
                    ps.executeUpdate();
                }

                if (auction.highestBidderUuid() != null) {
                    // Clear reserved currency
                    String clrSql = "UPDATE reserved_currency SET reserved_amount = 0 WHERE player_uuid = ?";
                    try (PreparedStatement psClr = conn.prepareStatement(clrSql)) {
                        psClr.setString(1, auction.highestBidderUuid().toString());
                        psClr.executeUpdate();
                    }
                }

                conn.commit();
                conn.setAutoCommit(true);

                // Main thread: deliver item to winner if online
                if (auction.highestBidderUuid() != null) {
                    Player winner = Bukkit.getPlayer(auction.highestBidderUuid());
                    if (winner != null && winner.isOnline()) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            currencyManager.removeCurrency(winner, auction.currentHighestBid());
                            ItemStack item = ItemStackConverter.itemStackFromBase64(auction.itemData());
                            if (item != null) {
                                winner.getInventory().addItem(item);
                            }
                            MessageUtils.sendMessage(winner, "&6Поздравляем! Вы выиграли аукцион за " + auction.currentHighestBid() + " монет!");
                        });
                    }
                }

            } catch (SQLException e) {
                plugin.getLogger().severe("Error completing auction #" + auction.id() + ": " + e.getMessage());
            }
        });
    }

    private AuctionData mapAuction(ResultSet rs) throws SQLException {
        return new AuctionData(
            rs.getInt("id"),
            rs.getInt("auctioneer_npc_id"),
            rs.getString("item_data"),
            rs.getInt("starting_price"),
            rs.getInt("current_highest_bid"),
            rs.getString("highest_bidder_uuid") != null ? UUID.fromString(rs.getString("highest_bidder_uuid")) : null,
            rs.getLong("starts_at"),
            rs.getLong("ends_at"),
            rs.getString("status"),
            rs.getString("winner_uuid") != null ? UUID.fromString(rs.getString("winner_uuid")) : null,
            rs.getObject("completed_at") != null ? rs.getLong("completed_at") : null
        );
    }
}
