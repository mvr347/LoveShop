package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class BuyerManager {

    private final LoveShops plugin;
    private final PriceCalculator priceCalculator;
    private final Random random = new Random();

    // Гвард от повторного входа: без него быстрый двойной клик (или два параллельных
    // вызова через LoveShopsAPI) успевают оба найти один и тот же стак в инвентаре ДО того,
    // как первый вызов дойдёт до его фактического удаления (оно раньше откладывалось до
    // возврата асинхронной записи в БД) — итог: предмет продан один раз, а монеты выданы
    // дважды. Один "в процессе" слот на игрока за раз.
    private final Set<UUID> processingSales = ConcurrentHashMap.newKeySet();

    public BuyerManager(LoveShops plugin, PriceCalculator priceCalculator) {
        this.plugin = plugin;
        this.priceCalculator = priceCalculator;
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

        // Единая точка входа для скупщика, барахолки (channel='seller') и аукциона по
        // порогу цены — запрет здесь перекрывает все три канала разом. AuctionManager
        // проверяет это же ещё раз на своей стороне для внешних вызовов (например, из LoveBrew).
        if (plugin.getForbiddenManager().isForbidden(item)) {
            player.sendMessage(MessageUtils.parse("<red>Этот предмет запрещено продавать!</red>"));
            future.complete(false);
            return future;
        }

        if (!processingSales.add(player.getUniqueId())) {
            // Уже есть сделка в процессе у этого игрока — второй клик игнорируем, а не
            // запускаем параллельно (см. комментарий у объявления processingSales).
            future.complete(false);
            return future;
        }

        getPlayerStatus(player.getUniqueId()).thenAccept(status -> {
            if ("bad".equalsIgnoreCase(status) || "aggressive".equalsIgnoreCase(status)) {
                Bukkit.getScheduler().runTask(plugin, () -> rejectPlayer(player, status));
                processingSales.remove(player.getUniqueId());
                future.complete(false);
                return;
            }

            LoveEconomy economy = plugin.getEconomy().orElse(null);

            // Must run inventory check on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (economy == null) {
                    processingSales.remove(player.getUniqueId());
                    future.complete(false);
                    return;
                }

                // Find matching item in player's inventory
                ItemStack matchInInventory = null;
                int matchSlot = -1;
                ItemStack[] contents = player.getInventory().getContents();

                for (int i = 0; i < contents.length; i++) {
                    ItemStack invItem = contents[i];
                    if (invItem == null || invItem.getType() == Material.AIR) continue;
                    if (economy.isCoin(invItem)) continue;

                    if (invItem.getType() == item.getType() && invItem.getAmount() >= item.getAmount()) {
                        matchInInventory = invItem;
                        matchSlot = i;
                        break;
                    }
                }

                if (matchInInventory == null) {
                    processingSales.remove(player.getUniqueId());
                    future.complete(false);
                    return;
                }

                int finalPrice = priceCalculator.calculateBuyPrice(player, item);
                int basePrice = priceCalculator.getBasePrice(item);
                int quantity = item.getAmount();

                // Списываем предмет из инвентаря немедленно, тем же тактом, что и поиск
                // совпадения выше — без единого пункта возврата в планировщик между ними
                // ни один другой клик/вызов не может увидеть этот стак ещё не тронутым.
                // Раньше удаление откладывалось до возврата из асинхронной записи в БД
                // (см. историю правок), и второй клик успевал найти тот же стек нетронутым,
                // что вело к однократной продаже предмета с двукратной выплатой монет.
                ItemStack slotItem = contents[matchSlot];
                int remaining = slotItem.getAmount() - quantity;
                if (remaining > 0) {
                    slotItem.setAmount(remaining);
                } else {
                    player.getInventory().setItem(matchSlot, null);
                }

                List<NpcData> buyerNpcs = plugin.getNpcManager().getNpcsByType("buyer");
                Integer npcId = buyerNpcs.isEmpty() ? null : buyerNpcs.get(0).id();

                String base64Item = ItemStackConverter.itemStackToBase64(item);
                String itemType = item.getType().name();

                boolean auctioneerEnabled = plugin.getConfig().getBoolean("auctioneer.enabled", true);
                int auctionThreshold = plugin.getConfig().getInt("auctioneer.price-threshold", 400);
                boolean isRare = plugin.getConfig().getBoolean("auctioneer.route-rare-items", true) && priceCalculator.isRareItem(item);
                String channel = (auctioneerEnabled && (isRare || basePrice * quantity >= auctionThreshold)) ? "auction" : "seller";

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
                        trackSubmission(conn, player.getUniqueId(), itemType);

                        conn.commit();

                        // Main thread: pay the player now that the sale is durably recorded
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            long taxedPrice = dev.lovelace.lovecore.api.LoveCore.service(dev.lovelace.lovecore.api.economy.TaxOracle.class)
                                    .map(tax -> tax.applyToPayout(player.getUniqueId(), finalPrice))
                                    .orElse((long) finalPrice);
                            economy.give(player, taxedPrice);

                            String acceptMsg = plugin.getConfig().getString("buyer.messages.accept", "&aСкупщик: Отличный товар! Вот тебе {price} монет!");
                            acceptMsg = acceptMsg.replace("{price}", String.valueOf(taxedPrice));
                            MessageUtils.sendMessage(player, acceptMsg);

                            processingSales.remove(player.getUniqueId());
                            future.complete(true);
                        });

                    } catch (SQLException e) {
                        plugin.getLogger().severe("Error processing buyer sale: " + e.getMessage());
                        // Предмет уже списан выше (до похода в БД) — если запись не удалась,
                        // возвращаем его игроку вместо того, чтобы просто проглотить ошибку
                        // и оставить его без предмета и без оплаты.
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            ItemStack refund = item.clone();
                            refund.setAmount(quantity);
                            for (ItemStack leftover : player.getInventory().addItem(refund).values()) {
                                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                            }
                            MessageUtils.sendMessage(player, "&cОшибка обработки продажи — предмет возвращён.");
                            processingSales.remove(player.getUniqueId());
                            future.complete(false);
                        });
                    }
                });
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
