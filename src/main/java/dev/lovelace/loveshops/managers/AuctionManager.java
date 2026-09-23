package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AuctionManager {

    private final LoveShops plugin;

    public AuctionManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Integer> createAuction(ItemStack item, int startingPrice) {
        CompletableFuture<Integer> future = new CompletableFuture<>();

        // Повторная проверка форбида здесь (не только в BuyerManager.processSale) —
        // единственный способ покрыть внешние вызовы вроде LoveBrew's LoveShopBridge,
        // которые обращаются к аукциону напрямую, минуя processSale.
        if (plugin.getForbiddenManager().isForbidden(item)) {
            future.completeExceptionally(new IllegalStateException("Этот предмет запрещено продавать: " + item.getType()));
            return future;
        }

        String base64Item = ItemStackConverter.itemStackToBase64(item);

        List<NpcData> npcs = plugin.getNpcManager().getNpcsByType("auctioneer");
        Integer npcId = npcs.isEmpty() ? null : npcs.get(0).id();

        long now = System.currentTimeMillis() / 1000;
        int hours = plugin.getConfig().getInt("auctioneer.auction-duration-hours", 24);
        long endsAt = now + (hours * 3600L);
        double buyoutMultiplier = plugin.getConfig().getDouble("auctioneer.buyout-multiplier", 2.5);
        int buyoutPrice = (int) Math.round(startingPrice * buyoutMultiplier);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO auctions (auctioneer_npc_id, item_data, starting_price, current_highest_bid, starts_at, ends_at, status, buyout_price)
                VALUES (?, ?, ?, ?, ?, ?, 'active', ?)
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
                ps.setInt(7, buyoutPrice);
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

    /**
     * Moves buyer purchases routed to the "auction" channel (expensive items, see
     * BuyerManager.processSale) into fresh auction lots. Called when the flea market opens.
     */
    /**
     * Moves buyer purchases routed to the "auction" channel (expensive items, see
     * BuyerManager.processSale) into fresh auction lots. Called when the flea market opens.
     */
    public void createAuctionsFromPendingItems() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            record PendingItem(int id, String itemData, int basePrice) {}
            List<PendingItem> pendingList = new ArrayList<>();

            String selectSql = "SELECT id, item_data, base_price FROM buyer_inventory " +
                "WHERE channel = 'auction' AND sold_at IS NULL AND auctioned_at IS NULL";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement select = conn.prepareStatement(selectSql)) {
                ResultSet rs = select.executeQuery();
                while (rs.next()) {
                    pendingList.add(new PendingItem(rs.getInt("id"), rs.getString("item_data"), rs.getInt("base_price")));
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error fetching pending auction items: " + e.getMessage());
                return;
            }

            for (PendingItem pending : pendingList) {
                ItemStack item = ItemStackConverter.itemStackFromBase64(pending.itemData());
                if (item == null) continue;

                try {
                    int auctionId = createAuctionSync(item, pending.basePrice());
                    if (auctionId > 0) {
                        try (Connection conn = plugin.getDatabaseManager().getConnection();
                             PreparedStatement mark = conn.prepareStatement(
                                 "UPDATE buyer_inventory SET auctioned_at = strftime('%s', 'now') WHERE id = ?")) {
                            mark.setInt(1, pending.id());
                            mark.executeUpdate();
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Error processing pending auction item #" + pending.id() + ": " + e.getMessage());
                }
            }
        });
    }

    private int createAuctionSync(ItemStack item, int startingPrice) throws SQLException {
        String base64Item = ItemStackConverter.itemStackToBase64(item);
        List<NpcData> npcs = plugin.getNpcManager().getNpcsByType("auctioneer");
        Integer npcId = npcs.isEmpty() ? null : npcs.get(0).id();

        long now = System.currentTimeMillis() / 1000;
        int hours = plugin.getConfig().getInt("auctioneer.auction-duration-hours", 24);
        long endsAt = now + (hours * 3600L);
        double buyoutMultiplier = plugin.getConfig().getDouble("auctioneer.buyout-multiplier", 2.5);
        int buyoutPrice = (int) Math.round(startingPrice * buyoutMultiplier);

        String sql = """
            INSERT INTO auctions (auctioneer_npc_id, item_data, starting_price, current_highest_bid, starts_at, ends_at, status, buyout_price)
            VALUES (?, ?, ?, ?, ?, ?, 'active', ?)
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
            ps.setInt(7, buyoutPrice);
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            return rs.next() ? rs.getInt(1) : 0;
        }
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

    /**
     * Новое время окончания с учётом анти-снайпа. Если до конца осталось меньше
     * порога, аукцион продлевается на настроенное время от текущего момента.
     * Возвращает прежнее время, когда продлевать нечего.
     */
    private long extendedEndTime(AuctionData auction) {
        if (!plugin.getConfig().getBoolean("auctioneer.anti-snipe.enabled", true)) {
            return auction.endsAt();
        }

        long thresholdSeconds = plugin.getConfig().getLong("auctioneer.anti-snipe.threshold-seconds", 30);
        long extensionSeconds = plugin.getConfig().getLong("auctioneer.anti-snipe.extension-seconds", 30);
        if (thresholdSeconds <= 0 || extensionSeconds <= 0) {
            return auction.endsAt();
        }

        long now = System.currentTimeMillis() / 1000;
        if (auction.endsAt() - now > thresholdSeconds) {
            return auction.endsAt();
        }
        return now + extensionSeconds;
    }

    public CompletableFuture<Boolean> placeBid(Player bidder, int auctionId, int bidAmount) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            AuctionData auction = null;
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                String selSql = "SELECT * FROM auctions WHERE id = ? AND status = 'active'";
                try (PreparedStatement ps = conn.prepareStatement(selSql)) {
                    ps.setInt(1, auctionId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        auction = mapAuction(rs);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error initiating bid lookup: " + e.getMessage());
                future.complete(false);
                return;
            }

            if (auction == null) {
                future.complete(false);
                return;
            }

            int minNextBid = getMinimumNextBid(auction);
            if (bidAmount < minNextBid) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    MessageUtils.sendMessage(bidder, "&cСтавка слишком мала! Минимальная ставка: " + minNextBid);
                });
                future.complete(false);
                return;
            }

            final AuctionData finalAuction = auction;
            Bukkit.getScheduler().runTask(plugin, () -> {
                LoveEconomy economy = plugin.getEconomy().orElse(null);
                if (economy == null || economy.balance(bidder) < bidAmount) {
                    MessageUtils.sendMessage(bidder, "&cНедостаточно средств на балансе!");
                    future.complete(false);
                    return;
                }

                // Perform database updates async with fresh connection
                Bukkit.getAsyncScheduler().runNow(plugin, bidTask -> {
                    try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                        conn.setAutoCommit(false);

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

                        // 3. Update auction record. Ставка в последние секунды продлевает
                        // аукцион: иначе лот забирает не тот, кто дал больше, а тот, кто
                        // успел кликнуть последним.
                        //
                        // Проверка bidAmount >= minNextBid выше сделана по снимку аукциона,
                        // прочитанному ДО этого момента — за время двух прыжков на главный
                        // поток и обратно другая ставка могла успеть примениться первой.
                        // WHERE current_highest_bid = ? — это compare-and-swap поверх того же
                        // снимка: если кто-то уже перебил ставку, обновление затронет 0 строк
                        // вместо того, чтобы вслепую перезаписать более высокую ставку более
                        // низкой (и увести "текущего лидера" на игрока, который на самом деле
                        // предложил меньше).
                        long extendedEndsAt = extendedEndTime(finalAuction);
                        String upAucSql = extendedEndsAt > finalAuction.endsAt()
                            ? "UPDATE auctions SET current_highest_bid = ?, highest_bidder_uuid = ?, ends_at = ? WHERE id = ? AND status = 'active' AND current_highest_bid = ?"
                            : "UPDATE auctions SET current_highest_bid = ?, highest_bidder_uuid = ? WHERE id = ? AND status = 'active' AND current_highest_bid = ?";
                        int auctionRowsUpdated;
                        try (PreparedStatement psUpAuc = conn.prepareStatement(upAucSql)) {
                            psUpAuc.setInt(1, bidAmount);
                            psUpAuc.setString(2, bidder.getUniqueId().toString());
                            if (extendedEndsAt > finalAuction.endsAt()) {
                                psUpAuc.setLong(3, extendedEndsAt);
                                psUpAuc.setInt(4, auctionId);
                                psUpAuc.setInt(5, finalAuction.currentHighestBid());
                            } else {
                                psUpAuc.setInt(3, auctionId);
                                psUpAuc.setInt(4, finalAuction.currentHighestBid());
                            }
                            auctionRowsUpdated = psUpAuc.executeUpdate();
                        }

                        if (auctionRowsUpdated == 0) {
                            conn.rollback();
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                MessageUtils.sendMessage(bidder, "&cВас опередили! Кто-то поставил ставку в то же мгновение — попробуйте снова.");
                                future.complete(false);
                            });
                            return;
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

                        final boolean extended = extendedEndsAt > finalAuction.endsAt();
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            MessageUtils.sendMessage(bidder, "&a✓ Ставка принята: " + bidAmount + " монет!");
                            if (extended) {
                                long addedSeconds = extendedEndsAt - System.currentTimeMillis() / 1000;
                                MessageUtils.sendMessage(bidder, "&eСтавка в последние секунды — аукцион продлён на " + Math.max(0, addedSeconds) + " сек.");
                            }
                            Bukkit.getPluginManager().callEvent(new AuctionBidPlacedEvent(bidder, finalAuction, bidAmount));
                            future.complete(true);
                        });

                    } catch (SQLException e) {
                        plugin.getLogger().severe("Error placing auction bid: " + e.getMessage());
                        future.complete(false);
                    }
                });
            });
        });

        return future;
    }

    public CompletableFuture<Boolean> buyoutAuction(Player buyer, int auctionId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            AuctionData auction = null;
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                String selSql = "SELECT * FROM auctions WHERE id = ? AND status = 'active'";
                try (PreparedStatement ps = conn.prepareStatement(selSql)) {
                    ps.setInt(1, auctionId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        auction = mapAuction(rs);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error initiating buyout lookup: " + e.getMessage());
                future.complete(false);
                return;
            }

            if (auction == null || auction.buyoutPrice() <= 0) {
                future.complete(false);
                return;
            }

            final AuctionData finalAuction = auction;
            final ItemStack itemStack = ItemStackConverter.itemStackFromBase64(finalAuction.itemData());

            Bukkit.getScheduler().runTask(plugin, () -> {
                long taxedBuyout = taxedAuctionPrice(buyer, finalAuction.buyoutPrice());
                if (!plugin.getEconomy().map(e -> e.has(buyer, taxedBuyout)).orElse(false)) {
                    MessageUtils.sendMessage(buyer, "&cНедостаточно средств для выкупа!");
                    future.complete(false);
                    return;
                }

                Bukkit.getAsyncScheduler().runNow(plugin, buyoutTask -> {
                    try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                        conn.setAutoCommit(false);

                        String upSql = "UPDATE auctions SET status = 'completed', winner_uuid = ?, current_highest_bid = ?, completed_at = strftime('%s', 'now') WHERE id = ? AND status = 'active'";
                        int updated;
                        try (PreparedStatement ps = conn.prepareStatement(upSql)) {
                            ps.setString(1, buyer.getUniqueId().toString());
                            ps.setInt(2, finalAuction.buyoutPrice());
                            ps.setInt(3, auctionId);
                            updated = ps.executeUpdate();
                        }

                        if (updated == 0) {
                            conn.rollback();
                            Bukkit.getScheduler().runTask(plugin, () -> future.complete(false));
                            return;
                        }

                        if (finalAuction.highestBidderUuid() != null) {
                            String clrSql = "UPDATE reserved_currency SET reserved_amount = 0 WHERE player_uuid = ?";
                            try (PreparedStatement psClr = conn.prepareStatement(clrSql)) {
                                psClr.setString(1, finalAuction.highestBidderUuid().toString());
                                psClr.executeUpdate();
                            }
                        }

                        conn.commit();

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // has() выше и charge() здесь разделены прыжком на асинхронный
                            // поток и обратно — баланс мог измениться. attemptDeliver
                            // проверяет заново и не выдаёт лот бесплатно, если денег вдруг
                            // не хватило: статус лота уже 'completed', так что при неудаче
                            // выдача останется в очереди и будет повторена deliverPendingWins.
                            boolean delivered = attemptDeliver(finalAuction.id(), buyer, itemStack, finalAuction.buyoutPrice());
                            if (delivered) {
                                MessageUtils.sendMessage(buyer, "&6Лот выкуплен за " + finalAuction.buyoutPrice() + " монет!");
                            } else {
                                MessageUtils.sendMessage(buyer, "&cВыкуп оформлен, но списать монеты не удалось — предмет будет выдан, как только на балансе появится нужная сумма.");
                            }
                            future.complete(true);
                        });
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Error buying out auction: " + e.getMessage());
                        future.complete(false);
                    }
                });
            });
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

                // Если победитель сейчас онлайн — пробуем выдать сразу; если нет (или не
                // хватило денег прямо сейчас), delivered_at останется NULL и лот подберёт
                // либо периодическая deliverPendingWins(), либо вход игрока в игру — раньше
                // предмет без онлайн-победителя в этот момент терялся навсегда.
                if (auction.highestBidderUuid() != null) {
                    Player winner = Bukkit.getPlayer(auction.highestBidderUuid());
                    if (winner != null && winner.isOnline()) {
                        ItemStack item = ItemStackConverter.itemStackFromBase64(auction.itemData());
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            boolean delivered = attemptDeliver(auction.id(), winner, item, auction.currentHighestBid());
                            if (delivered) {
                                MessageUtils.sendMessage(winner, "&6Поздравляем! Вы выиграли аукцион за " + auction.currentHighestBid() + " монет!");
                            }
                        });
                    }
                }

            } catch (SQLException e) {
                plugin.getLogger().severe("Error completing auction #" + auction.id() + ": " + e.getMessage());
            }
        });
    }

    /**
     * Подбирает завершённые аукционы, чьи победители ещё не получили лот (например, были
     * офлайн в момент завершения или не хватило денег на тот момент), и выдаёт их тем, кто
     * сейчас в сети. Вызывается периодически из {@code ScheduleListener} и при входе игрока.
     */
    public void deliverPendingWins() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<AuctionData> pending = new ArrayList<>();
            String sql = "SELECT * FROM auctions WHERE status = 'completed' AND winner_uuid IS NOT NULL AND delivered_at IS NULL";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    pending.add(mapAuction(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Error fetching pending auction deliveries: " + e.getMessage());
                return;
            }

            for (AuctionData auction : pending) {
                Player winner = Bukkit.getPlayer(auction.winnerUuid());
                if (winner == null || !winner.isOnline()) continue;
                ItemStack item = ItemStackConverter.itemStackFromBase64(auction.itemData());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean delivered = attemptDeliver(auction.id(), winner, item, auction.currentHighestBid());
                    if (delivered) {
                        MessageUtils.sendMessage(winner, "&6Вам выдан выигранный лот аукциона (" + auction.currentHighestBid() + " монет)!");
                    }
                });
            }
        });
    }

    /** Отдельную выдачу конкретному игроку — используется при входе в игру ({@code ScheduleListener}). */
    public void deliverPendingWinsFor(Player winner) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            List<AuctionData> pending = new ArrayList<>();
            String sql = "SELECT * FROM auctions WHERE status = 'completed' AND winner_uuid = ? AND delivered_at IS NULL";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, winner.getUniqueId().toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    pending.add(mapAuction(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Error fetching pending auction deliveries for " + winner.getName() + ": " + e.getMessage());
                return;
            }

            for (AuctionData auction : pending) {
                ItemStack item = ItemStackConverter.itemStackFromBase64(auction.itemData());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!winner.isOnline()) return;
                    boolean delivered = attemptDeliver(auction.id(), winner, item, auction.currentHighestBid());
                    if (delivered) {
                        MessageUtils.sendMessage(winner, "&6Вам выдан выигранный лот аукциона (" + auction.currentHighestBid() + " монет)!");
                    }
                });
            }
        });
    }

    /** Цена выкупа/победы в аукционе с учётом единого налога LoveCore (или без изменений, если налог недоступен). */
    private long taxedAuctionPrice(Player player, int price) {
        return dev.lovelace.lovecore.api.LoveCore.service(dev.lovelace.lovecore.api.economy.TaxOracle.class)
                .map(tax -> tax.applyToCost(player.getUniqueId(), price))
                .orElse((long) price);
    }

    /**
     * Списывает деньги и выдаёт лот победителю, только если оплата действительно прошла —
     * {@code economy.charge} возвращает {@code false} без изменений в инвентаре, если монет
     * не хватило (физическая валюта LoveCore, баланс мог измениться асинхронно с момента
     * последней проверки). Раньше результат charge() игнорировался и предмет выдавался
     * безусловно — тем самым игрок мог получить лот бесплатно. При неудаче delivered_at не
     * проставляется, и выдача будет повторена позже без повторного списания (проверяем и
     * списываем заново, а не полагаемся на уже потраченный расчёт).
     *
     * @return {@code true}, если лот выдан и монеты списаны
     */
    private boolean attemptDeliver(int auctionId, Player winner, ItemStack item, int price) {
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) return false;

        long taxedPrice = taxedAuctionPrice(winner, price);
        if (!economy.charge(winner, taxedPrice)) {
            return false;
        }

        if (item != null) {
            Map<Integer, ItemStack> leftover = winner.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                for (ItemStack drop : leftover.values()) {
                    winner.getWorld().dropItemNaturally(winner.getLocation(), drop);
                }
            }
        }

        markDelivered(auctionId);
        return true;
    }

    private void markDelivered(int auctionId) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "UPDATE auctions SET delivered_at = strftime('%s', 'now') WHERE id = ?")) {
                ps.setInt(1, auctionId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error marking auction #" + auctionId + " as delivered: " + e.getMessage());
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
            rs.getObject("completed_at") != null ? rs.getLong("completed_at") : null,
            rs.getInt("buyout_price")
        );
    }
}
