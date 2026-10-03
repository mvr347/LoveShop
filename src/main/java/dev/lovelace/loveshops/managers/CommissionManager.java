package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.CommissionAgentGui;
import dev.lovelace.loveshops.models.commission.CommissionLot;
import dev.lovelace.loveshops.utils.CaravanEffects;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Менеджер Комиссионера (Commission Agent):
 * - Выставление лотов игроками (1 лот на игрока)
 * - Покупка лотов другими игроками с комиссией брокера (10%)
 * - Защита от покупки своего собственного лота
 * - Выплата монет продавцу (сразу если онлайн, через отложенные выплаты если офлайн)
 * - Система «горячих предложений» (is_hot) при высокой активности
 * - Снятие/возврат непроданных лотов
 */
public class CommissionManager {

    public enum LotResult {
        SUCCESS,
        ALREADY_HAS_LOT,
        FORBIDDEN_ITEM,
        INVALID_PRICE,
        INVALID_ITEM,
        NOT_FOUND,
        CANT_BUY_SELF,
        NO_MONEY,
        NO_SPACE,
        DB_ERROR
    }

    private final LoveShops plugin;

    public CommissionManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("commission.enabled", true);
    }

    public int getFeePercent() {
        return Math.max(0, Math.min(100, plugin.getConfig().getInt("commission.fee-percent", 10)));
    }

    public int getMaxLotsPerPlayer() {
        return Math.max(1, plugin.getConfig().getInt("commission.max-lots-per-player", 1));
    }

    public int getMaxItemsPerLot() {
        return Math.max(1, plugin.getConfig().getInt("commission.max-items-per-lot", 64));
    }

    /**
     * Создание нового лота на комиссии.
     */
    public synchronized LotResult createLot(Player seller, ItemStack item, int price) {
        if (!isEnabled()) return LotResult.DB_ERROR;
        if (seller == null || !seller.isOnline()) return LotResult.INVALID_ITEM;
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return LotResult.INVALID_ITEM;

        if (price <= 0 || price > 100_000_000) {
            return LotResult.INVALID_PRICE;
        }

        if (item.getAmount() > getMaxItemsPerLot()) {
            return LotResult.INVALID_ITEM;
        }

        if (plugin.getForbiddenManager().isForbidden(item.getType())) {
            return LotResult.FORBIDDEN_ITEM;
        }

        UUID sellerUuid = seller.getUniqueId();

        // Проверка лимита лотов
        if (getPlayerActiveLotsCount(sellerUuid) >= getMaxLotsPerPlayer()) {
            return LotResult.ALREADY_HAS_LOT;
        }

        String base64 = ItemStackConverter.itemStackToBase64(item);
        int feePercent = getFeePercent();

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO commission_lots (seller_uuid, item_data, price, fee_percent, status, is_hot)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0)
            """);
            ps.setString(1, sellerUuid.toString());
            ps.setString(2, base64);
            ps.setInt(3, price);
            ps.setInt(4, feePercent);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка выставления лота у комиссионера: " + e.getMessage());
            return LotResult.DB_ERROR;
        }

        seller.playSound(seller.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.1f);
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        MessageUtils.sendMessage(seller, "<green>Лот успешно выставлен за "
                + CoinFormat.formatGlyphs(eco, price) + "! Комиссия брокера при продаже составит " + feePercent + "%.</green>");

        return LotResult.SUCCESS;
    }

    /**
     * Покупка лота другим игроком.
     */
    public synchronized LotResult buyLot(Player buyer, int lotId) {
        if (!isEnabled()) return LotResult.DB_ERROR;
        if (buyer == null || !buyer.isOnline()) return LotResult.DB_ERROR;

        CommissionLot lot = getLotById(lotId).orElse(null);
        if (lot == null || !"ACTIVE".equalsIgnoreCase(lot.status())) {
            return LotResult.NOT_FOUND;
        }

        if (buyer.getUniqueId().equals(lot.sellerUuid())) {
            return LotResult.CANT_BUY_SELF;
        }

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null) return LotResult.DB_ERROR;

        if (!eco.has(buyer, lot.price())) {
            return LotResult.NO_MONEY;
        }

        if (buyer.getInventory().firstEmpty() == -1 && !canMergeIntoInventory(buyer, lot.item())) {
            return LotResult.NO_SPACE;
        }

        // Атомарное обновление статуса лота в БД
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                UPDATE commission_lots
                SET status = 'SOLD', sold_at = strftime('%s','now'), buyer_uuid = ?
                WHERE id = ? AND status = 'ACTIVE'
            """);
            ps.setString(1, buyer.getUniqueId().toString());
            ps.setInt(2, lotId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                return LotResult.NOT_FOUND; // Лот уже купили или сняли
            }

            // Запись в историю продаж
            PreparedStatement psHist = conn.prepareStatement("""
                INSERT INTO commission_sales_history (lot_id, seller_uuid, buyer_uuid, price, fee, sold_at)
                VALUES (?, ?, ?, ?, ?, strftime('%s','now'))
            """);
            psHist.setInt(1, lotId);
            psHist.setString(2, lot.sellerUuid().toString());
            psHist.setString(3, buyer.getUniqueId().toString());
            psHist.setInt(4, lot.price());
            psHist.setInt(5, lot.feeAmount());
            psHist.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка покупки лота комиссии: " + e.getMessage());
            return LotResult.DB_ERROR;
        }

        // 1. Списание монет у покупателя
        if (!eco.charge(buyer, lot.price())) {
            // Откат статуса лота в БД
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                PreparedStatement psRollback = conn.prepareStatement("""
                    UPDATE commission_lots SET status = 'ACTIVE', sold_at = NULL, buyer_uuid = NULL WHERE id = ?
                """);
                psRollback.setInt(1, lotId);
                psRollback.executeUpdate();

                PreparedStatement psHistDel = conn.prepareStatement("""
                    DELETE FROM commission_sales_history WHERE lot_id = ? AND buyer_uuid = ?
                """);
                psHistDel.setInt(1, lotId);
                psHistDel.setString(2, buyer.getUniqueId().toString());
                psHistDel.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка отката статуса лота комиссии: " + e.getMessage());
            }
            return LotResult.NO_MONEY;
        }

        // 2. Выдача предмета покупателю
        var leftover = buyer.getInventory().addItem(lot.item().clone());
        if (!leftover.isEmpty()) {
            for (ItemStack drop : leftover.values()) {
                buyer.getWorld().dropItemNaturally(buyer.getLocation(), drop);
            }
            MessageUtils.sendMessage(buyer, "<yellow>[Комиссионер] В инвентаре не хватило места, часть предметов выпала на землю!</yellow>");
        }
        buyer.playSound(buyer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        MessageUtils.sendMessage(buyer, "<green>[Комиссионер] Вы успешно приобрели предмет за "
                + CoinFormat.formatGlyphs(eco, lot.price()) + "!</green>");

        // 3. Выплата продавцу (за вычетом комиссии)
        int payout = lot.sellerReceives();
        Player seller = Bukkit.getPlayer(lot.sellerUuid());
        if (seller != null && seller.isOnline()) {
            if (eco.canFit(seller, payout)) {
                eco.give(seller, payout);
                CaravanEffects.playSubmitEffects(seller);
                MessageUtils.sendMessage(seller, "<green>[Комиссионер] Ваш лот был куплен игроком <gold>"
                        + buyer.getName() + "</gold>! Вам начислено " + CoinFormat.formatGlyphs(eco, payout)
                        + " (комиссия " + lot.feePercent() + "%).</green>");
            } else {
                // Инвентарь полон -> откладываем в pending_payouts
                addPendingPayout(lot.sellerUuid(), payout);
                MessageUtils.sendMessage(seller, "<yellow>[Комиссионер] Ваш лот был куплен игроком <gold>"
                        + buyer.getName() + "</gold>, но ваш инвентарь полон! Монеты (" + CoinFormat.formatGlyphs(eco, payout)
                        + ") сохранены в выплатах и будут выданы, когда вы освободите место.</yellow>");
            }
        } else {
            // Офлайн выплата
            addPendingPayout(lot.sellerUuid(), payout);
        }

        // 4. Проверка и обновление статуса «горячее предложение»
        checkAndUpdateHotOffers(lot.item().getType().name());

        return LotResult.SUCCESS;
    }

    /**
     * Снятие лота с продажи продавцом.
     */
    public synchronized LotResult cancelLot(Player seller, int lotId) {
        if (seller == null || !seller.isOnline()) return LotResult.DB_ERROR;

        CommissionLot lot = getLotById(lotId).orElse(null);
        if (lot == null || !"ACTIVE".equalsIgnoreCase(lot.status())) {
            return LotResult.NOT_FOUND;
        }

        if (!seller.getUniqueId().equals(lot.sellerUuid())) {
            return LotResult.NOT_FOUND;
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                UPDATE commission_lots SET status = 'CANCELLED' WHERE id = ? AND seller_uuid = ? AND status = 'ACTIVE'
            """);
            ps.setInt(1, lotId);
            ps.setString(2, seller.getUniqueId().toString());
            int updated = ps.executeUpdate();
            if (updated == 0) return LotResult.NOT_FOUND;
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка отмены лота комиссии: " + e.getMessage());
            return LotResult.DB_ERROR;
        }

        // Возврат предмета
        var leftover = seller.getInventory().addItem(lot.item().clone());
        if (!leftover.isEmpty()) {
            for (ItemStack drop : leftover.values()) {
                seller.getWorld().dropItemNaturally(seller.getLocation(), drop);
            }
            MessageUtils.sendMessage(seller, "<yellow>Ваш инвентарь был полон, предмет выпал рядом с вами!</yellow>");
        }

        seller.playSound(seller.getLocation(), Sound.BLOCK_CHEST_CLOSE, 1.0f, 1.0f);
        MessageUtils.sendMessage(seller, "<yellow>[Комиссионер] Лот снят с продажи, предмет возвращён в ваш инвентарь.</yellow>");
        return LotResult.SUCCESS;
    }

    /**
     * Проверка и начисление отложенных выплат офлайн-продавцу при входе.
     */
    public void claimPendingPayouts(Player player) {
        if (player == null || !player.isOnline()) return;
        UUID uuid = player.getUniqueId();

        long total = 0;
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT id, amount FROM commission_pending_payouts WHERE seller_uuid = ?
            """);
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            List<Integer> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getInt("id"));
                total += rs.getLong("amount");
            }

            if (total > 0 && !ids.isEmpty()) {
                LoveEconomy eco = plugin.getEconomy().orElse(null);
                if (eco != null) {
                    if (!eco.canFit(player, total)) {
                        MessageUtils.sendMessage(player, "<gold><bold>📦 [Комиссионер]</bold></gold> <yellow>У вас есть отложенная выплата за продажу лотов ("
                                + CoinFormat.formatGlyphs(eco, total) + "), но в вашем инвентаре недостаточно места для монет! Освободите слоты.</yellow>");
                        return;
                    }

                    eco.give(player, total);

                    PreparedStatement psDel = conn.prepareStatement(
                            "DELETE FROM commission_pending_payouts WHERE seller_uuid = ?"
                    );
                    psDel.setString(1, uuid.toString());
                    psDel.executeUpdate();

                    CaravanEffects.playSubmitEffects(player);
                    long finalTotal = total;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            MessageUtils.sendMessage(player, "<gold><bold>📦 [Комиссионер]</bold></gold> <green>Пока вас не было в сети, были проданы ваши лоты! Вам начислено: "
                                    + CoinFormat.formatGlyphs(eco, finalTotal) + ".</green>");
                        }
                    }, 40L);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка выдачи отложенных выплат комиссии: " + e.getMessage());
        }
    }

    private void addPendingPayout(UUID sellerUuid, int amount) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO commission_pending_payouts (seller_uuid, amount) VALUES (?, ?)
            """);
            ps.setString(1, sellerUuid.toString());
            ps.setInt(2, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка сохранения офлайн-выплаты комиссии: " + e.getMessage());
        }
    }

    public List<CommissionLot> getActiveLots(int page, int pageSize) {
        List<CommissionLot> list = new ArrayList<>();
        int offset = Math.max(0, page * pageSize);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT id, seller_uuid, item_data, price, fee_percent, status, created_at, is_hot
                FROM commission_lots
                WHERE status = 'ACTIVE'
                ORDER BY is_hot DESC, id DESC
                LIMIT ? OFFSET ?
            """);
            ps.setInt(1, pageSize);
            ps.setInt(2, offset);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ItemStack item = ItemStackConverter.itemStackFromBase64(rs.getString("item_data"));
                if (item == null) continue;
                list.add(new CommissionLot(
                        rs.getInt("id"),
                        UUID.fromString(rs.getString("seller_uuid")),
                        item,
                        rs.getInt("price"),
                        rs.getInt("fee_percent"),
                        rs.getString("status"),
                        rs.getLong("created_at"),
                        rs.getInt("is_hot") == 1
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки активных лотов комиссии: " + e.getMessage());
        }
        return list;
    }

    public int getActiveLotsCount() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM commission_lots WHERE status = 'ACTIVE'");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка подсчёта активных лотов комиссии: " + e.getMessage());
        }
        return 0;
    }

    public Optional<CommissionLot> getPlayerActiveLot(UUID playerUuid) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT id, seller_uuid, item_data, price, fee_percent, status, created_at, is_hot
                FROM commission_lots
                WHERE seller_uuid = ? AND status = 'ACTIVE'
                ORDER BY id DESC LIMIT 1
            """);
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ItemStack item = ItemStackConverter.itemStackFromBase64(rs.getString("item_data"));
                if (item != null) {
                    return Optional.of(new CommissionLot(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("seller_uuid")),
                            item,
                            rs.getInt("price"),
                            rs.getInt("fee_percent"),
                            rs.getString("status"),
                            rs.getLong("created_at"),
                            rs.getInt("is_hot") == 1
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения лота игрока: " + e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<CommissionLot> getLotById(int lotId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT id, seller_uuid, item_data, price, fee_percent, status, created_at, is_hot
                FROM commission_lots WHERE id = ?
            """);
            ps.setInt(1, lotId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ItemStack item = ItemStackConverter.itemStackFromBase64(rs.getString("item_data"));
                if (item != null) {
                    return Optional.of(new CommissionLot(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("seller_uuid")),
                            item,
                            rs.getInt("price"),
                            rs.getInt("fee_percent"),
                            rs.getString("status"),
                            rs.getLong("created_at"),
                            rs.getInt("is_hot") == 1
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения лота комиссии по ID: " + e.getMessage());
        }
        return Optional.empty();
    }

    private int getPlayerActiveLotsCount(UUID playerUuid) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM commission_lots WHERE seller_uuid = ? AND status = 'ACTIVE'
            """);
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка подсчёта лотов игрока: " + e.getMessage());
        }
        return 0;
    }

    private void checkAndUpdateHotOffers(String materialName) {
        int threshold = Math.max(1, plugin.getConfig().getInt("commission.hot-offer-threshold-sales", 3));
        long dayAgo = (System.currentTimeMillis() / 1000) - 86400L;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) AS sales_count FROM commission_sales_history WHERE sold_at >= ?
            """);
            ps.setLong(1, dayAgo);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt("sales_count") >= threshold) {
                // Помечаем активные лоты этого типа как горячие
                PreparedStatement psHot = conn.prepareStatement("""
                    UPDATE commission_lots SET is_hot = 1 WHERE status = 'ACTIVE'
                """);
                psHot.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки горячих предложений комиссии: " + e.getMessage());
        }
    }

    private boolean canMergeIntoInventory(Player player, ItemStack item) {
        for (ItemStack is : player.getInventory().getStorageContents()) {
            if (is != null && is.isSimilar(item) && is.getAmount() + item.getAmount() <= is.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public void openGui(Player player) {
        new CommissionAgentGui(plugin, player, this, 0).open();
    }
}
