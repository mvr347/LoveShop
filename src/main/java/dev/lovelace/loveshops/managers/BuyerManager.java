package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
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
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BuyerManager {

    private final LoveShops plugin;
    private final PriceCalculator priceCalculator;
    private final CurrencyManager currencyManager;
    private final Random random = new Random();

    public BuyerManager(LoveShops plugin, PriceCalculator priceCalculator, CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.priceCalculator = priceCalculator;
        this.currencyManager = currencyManager;
    }

    public CompletableFuture<String> getPlayerStatus(UUID playerUuid) {
        CompletableFuture<String> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM buyer_reputation_overrides WHERE player_uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    future.complete(rs.getString("status"));
                } else {
                    future.complete("default");
                }
            } catch (SQLException e) {
                future.complete("default");
            }
        });
        return future;
    }

    public CompletableFuture<Void> setPlayerStatus(UUID playerUuid, String status, String setBy, String customMessage) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO buyer_reputation_overrides (player_uuid, status, set_by, custom_message)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET status=EXCLUDED.status, set_by=EXCLUDED.set_by, custom_message=EXCLUDED.custom_message
            """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, status.toLowerCase());
                ps.setString(3, setBy);
                ps.setString(4, customMessage);
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().severe("Error setting buyer status: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void rejectPlayer(Player player, String status) {
        List<String> messages;
        if ("bad".equalsIgnoreCase(status)) {
            messages = plugin.getConfig().getStringList("buyer.messages.reject-bad-reputation");
        } else if ("aggressive".equalsIgnoreCase(status)) {
            messages = plugin.getConfig().getStringList("buyer.messages.reject-aggressive");
        } else {
            messages = List.of("&cСкупщик отказывается иметь с вами дело.");
        }

        if (!messages.isEmpty()) {
            String selected = messages.get(random.nextInt(messages.size()));
            MessageUtils.sendMessage(player, selected);
        }
    }

    public CompletableFuture<Boolean> processSale(Player player, ItemStack item) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        if (item == null || item.getAmount() <= 0) {
            future.complete(false);
            return future;
        }

        getPlayerStatus(player.getUniqueId()).thenAccept(status -> {
            if ("bad".equalsIgnoreCase(status) || "aggressive".equalsIgnoreCase(status)) {
                Bukkit.getScheduler().runTask(plugin, () -> rejectPlayer(player, status));
                future.complete(false);
                return;
            }

            int finalPrice = priceCalculator.calculateBuyPrice(player, item);
            int basePrice = priceCalculator.getBasePrice(item);

            // Fetch buyer NPC ID (if any)
            List<NpcData> buyerNpcs = plugin.getNpcManager().getNpcsByType("buyer");
            Integer npcId = buyerNpcs.isEmpty() ? null : buyerNpcs.get(0).id();

            String base64Item = ItemStackConverter.itemStackToBase64(item);
            int quantity = item.getAmount();
            String itemType = item.getType().name();

            boolean auctioneerEnabled = plugin.getConfig().getBoolean("auctioneer.enabled", true);
            int auctionThreshold = plugin.getConfig().getInt("auctioneer.price-threshold", 400);
            String channel = (auctioneerEnabled && basePrice * quantity >= auctionThreshold) ? "auction" : "seller";

            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                    conn.setAutoCommit(false);

                    // Insert into buyer_inventory
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO buyer_inventory (npc_id, player_uuid, item_data, base_price, quantity, item_type, channel) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        if (npcId != null) {
                            ps.setInt(1, npcId);
                        } else {
                            ps.setNull(1, java.sql.Types.INTEGER);
                        }
                        ps.setString(2, player.getUniqueId().toString());
                        ps.setString(3, base64Item);
                        ps.setInt(4, basePrice);
                        ps.setInt(5, quantity);
                        ps.setString(6, itemType);
                        ps.setString(7, channel);
                        ps.executeUpdate();
                    }

                    // Update price history penalty
                    trackSubmission(conn, player.getUniqueId(), item.getType().name());

                    conn.commit();
                    conn.setAutoCommit(true);

                    // Main thread: remove item from player inventory & give coins
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.getInventory().removeItem(item);
                        currencyManager.giveCurrency(player, finalPrice);

                        String acceptMsg = plugin.getConfig().getString("buyer.messages.accept", "&aСкупщик: Отличный товар! Вот тебе {price} монет!");
                        acceptMsg = acceptMsg.replace("{price}", String.valueOf(finalPrice));
                        MessageUtils.sendMessage(player, acceptMsg);

                        future.complete(true);
                    });

                } catch (SQLException e) {
                    plugin.getLogger().severe("Error processing buyer sale: " + e.getMessage());
                    future.complete(false);
                }
            });
        });

        return future;
    }

    private void trackSubmission(Connection conn, UUID playerUuid, String itemType) throws SQLException {
        double penaltyPerSubmit = plugin.getConfig().getDouble("buyer.repetition-penalty.penalty-per-submit", 5.0);
        double maxPenalty = plugin.getConfig().getDouble("buyer.repetition-penalty.max-penalty", 50.0);

        String sql = """
            INSERT INTO buyer_prices_history (player_uuid, item_type, submit_count, price_penalty_percent)
            VALUES (?, ?, 1, ?)
            ON CONFLICT(player_uuid, item_type) DO UPDATE SET
                submit_count = submit_count + 1,
                price_penalty_percent = MIN(?, price_penalty_percent + ?),
                last_submitted_at = strftime('%s', 'now')
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, itemType);
            ps.setDouble(3, penaltyPerSubmit);
            ps.setDouble(4, maxPenalty);
            ps.setDouble(5, penaltyPerSubmit);
            ps.executeUpdate();
        }
    }
}
