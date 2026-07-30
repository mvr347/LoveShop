package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.BuyerItemData;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PriceCalculator {

    private final LoveShops plugin;
    private final Random random = new Random();

    public PriceCalculator(LoveShops plugin) {
        this.plugin = plugin;
    }

    public int getBasePrice(ItemStack item) {
        if (item == null) return 0;

        // Клановые артефакты ценятся не по материалу, а по типу: иначе боевой рог
        // ушёл бы на барахолку по цене обычного предмета того же материала.
        String artifactType = artifactTypeOf(item);
        if (artifactType != null) {
            int artifactPrice = plugin.getConfig().getInt("prices-config.artifacts." + artifactType, -1);
            if (artifactPrice > 0) {
                return artifactPrice;
            }
            return plugin.getConfig().getInt("prices-config.artifacts.default-price", 2500);
        }

        String materialName = item.getType().name();
        int configuredPrice = plugin.getConfig().getInt("prices-config." + materialName, -1);
        if (configuredPrice > 0) {
            return configuredPrice;
        }
        return plugin.getConfig().getInt("buyer.base-price-config.default-price", 100);
    }

    /**
     * Тип кланового артефакта, если предмет им является. Читается прямо из метки,
     * которую ставит LoveClans, — так связка работает без зависимости на его классы
     * и молча выключается, если плагин кланов на сервере не стоит.
     */
    public String artifactTypeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        NamespacedKey key = new NamespacedKey("loveclans", "artifact");
        return item.getItemMeta().getPersistentDataContainer()
                .get(key, PersistentDataType.STRING);
    }

    public double getRandomVariancePercent() {
        double min = plugin.getConfig().getDouble("buyer.price-variance.min-percent", -30.0);
        double max = plugin.getConfig().getDouble("buyer.price-variance.max-percent", 30.0);
        return min + (max - min) * random.nextDouble();
    }

    public double getPenaltyPercent(UUID playerUuid, String itemType) {
        if (!plugin.getConfig().getBoolean("buyer.repetition-penalty.enabled", true)) {
            return 0.0;
        }
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT price_penalty_percent FROM buyer_prices_history WHERE player_uuid = ? AND item_type = ?")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, itemType);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getDouble("price_penalty_percent");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error reading price penalty: " + e.getMessage());
        }
        return 0.0;
    }

    /** История сдачи одного типа предмета: сколько раз сдавали и на сколько за это срезана цена. */
    public record SubmissionHistory(int submitCount, double penaltyPercent) {}

    /**
     * Вся история сдачи предмета игроком одним запросом — для меню скупщика,
     * где иначе пришлось бы дёргать базу на каждый предмет в инвентаре.
     */
    public Map<String, SubmissionHistory> getSubmissionHistory(UUID playerUuid) {
        Map<String, SubmissionHistory> history = new HashMap<>();
        if (!plugin.getConfig().getBoolean("buyer.repetition-penalty.enabled", true)) {
            return history;
        }
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT item_type, submit_count, price_penalty_percent FROM buyer_prices_history WHERE player_uuid = ?")) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                history.put(rs.getString("item_type"),
                    new SubmissionHistory(rs.getInt("submit_count"), rs.getDouble("price_penalty_percent")));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error reading submission history: " + e.getMessage());
        }
        return history;
    }

    /** Куда движется цена лота относительно спокойной цены без спроса, предложения и шума. */
    public enum PriceTrend { RISING, FALLING, STABLE }

    /** Цена лота без динамики — только базовая цена и наценка барахолки. */
    public int getNeutralSellPrice(int basePrice) {
        double markup = plugin.getConfig().getDouble("seller.markup-percent", 15.0);
        return Math.max(1, (int) Math.round(basePrice * (1.0 + markup / 100.0)));
    }

    /**
     * Тренд цены лота. Спрос, предложение и шум цикла уже собираются в базе,
     * но игрок их не видел — цена просто менялась между заходами в меню.
     */
    public PriceTrend getTrend(int basePrice, int currentPrice) {
        int neutral = getNeutralSellPrice(basePrice);
        if (neutral <= 0) return PriceTrend.STABLE;

        double thresholdPercent = plugin.getConfig().getDouble("seller.dynamic-pricing.trend-threshold-percent", 5.0);
        double deltaPercent = (currentPrice - neutral) * 100.0 / neutral;

        if (deltaPercent > thresholdPercent) return PriceTrend.RISING;
        if (deltaPercent < -thresholdPercent) return PriceTrend.FALLING;
        return PriceTrend.STABLE;
    }

    /**
     * Надбавка или штраф к цене скупки по репутации игрока. Раньше здесь была рефлексия на
     * {@code dev.lovelace.lovebehavior.api.LoveBehaviorAPI.getReputation(UUID)} — пакета с таким
     * именем в LoveBehavior нет и не было (реальный — {@code me.lovelace.lovebehavior.api}), так
     * что интеграция не срабатывала никогда, и всё, кроме «good», давало ровно ноль.
     *
     * <p>{@code ReputationOracle} ядра возвращает пять ступеней вместо одной строки: bad-status
     * из конфига (раньше не использовался вовсе) теперь тоже применяется.</p>
     */
    public int getReputationBonusPercent(Player player) {
        if (Bukkit.getPluginManager().getPlugin("LoveCore") == null) {
            return 0;
        }
        try {
            return dev.lovelace.lovecore.api.LoveCore
                    .service(dev.lovelace.lovecore.api.social.ReputationOracle.class)
                    .map(oracle -> reputationBonusFor(oracle.tier(player.getUniqueId())))
                    .orElse(0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private int reputationBonusFor(dev.lovelace.lovecore.api.social.ReputationOracle.Tier tier) {
        return switch (tier) {
            case RESPECTED, GOOD -> plugin.getConfig().getInt("buyer.reputation-bonus.good-status", 20);
            case BAD, OUTCAST -> plugin.getConfig().getInt("buyer.reputation-bonus.bad-status", -50);
            case NEUTRAL -> 0;
        };
    }

    public int calculateBuyPrice(Player player, ItemStack item) {
        if (item == null) return 0;

        int basePrice = getBasePrice(item);
        double variancePercent = getRandomVariancePercent();
        double penaltyPercent = getPenaltyPercent(player.getUniqueId(), item.getType().name());
        int repBonusPercent = getReputationBonusPercent(player);

        double multiplier = 1.0;
        multiplier += (variancePercent / 100.0);
        multiplier -= (penaltyPercent / 100.0);
        multiplier += (repBonusPercent / 100.0);

        int singleUnitPrice = (int) Math.round(basePrice * Math.max(0.1, multiplier));
        return Math.max(1, singleUnitPrice * item.getAmount());
    }

    // ===== Flea market (seller) dynamic pricing: markup + demand + supply + per-cycle noise =====

    public CompletableFuture<Map<Integer, Integer>> calculateSellPrices(List<BuyerItemData> items) {
        CompletableFuture<Map<Integer, Integer>> future = new CompletableFuture<>();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            Map<Integer, Integer> prices = new HashMap<>();
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                for (BuyerItemData item : items) {
                    prices.put(item.id(), calculateSellPrice(conn, item));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Error calculating seller prices: " + e.getMessage());
            }
            future.complete(prices);
        });
        return future;
    }

    public int calculateSellPrice(Connection conn, BuyerItemData itemData) throws SQLException {
        double markup = plugin.getConfig().getDouble("seller.markup-percent", 15.0);

        if (!plugin.getConfig().getBoolean("seller.dynamic-pricing.enabled", true) || itemData.itemType() == null) {
            return Math.max(1, (int) Math.round(itemData.basePrice() * (1.0 + markup / 100.0)));
        }

        double demandWeight = plugin.getConfig().getDouble("seller.dynamic-pricing.demand-weight-percent", 3.0);
        double supplyWeight = plugin.getConfig().getDouble("seller.dynamic-pricing.supply-weight-percent", 2.0);
        double minMultiplier = plugin.getConfig().getDouble("seller.dynamic-pricing.min-multiplier", 0.3);

        int demandCount = 0;
        double noisePercent = 0.0;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT demand_count, noise_percent FROM seller_price_state WHERE item_type = ?")) {
            ps.setString(1, itemData.itemType());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                demandCount = rs.getInt("demand_count");
                noisePercent = rs.getDouble("noise_percent");
            }
        }

        int supplyCount;
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT COUNT(*) AS cnt FROM buyer_inventory WHERE sold_at IS NULL AND channel = 'seller' AND item_type = ?")) {
            ps.setString(1, itemData.itemType());
            ResultSet rs = ps.executeQuery();
            supplyCount = rs.next() ? rs.getInt("cnt") : 0;
        }

        double multiplier = 1.0 + (markup / 100.0);
        multiplier *= (1.0 + (demandCount * demandWeight) / 100.0);
        multiplier *= Math.max(minMultiplier, 1.0 - (supplyCount * supplyWeight) / 100.0);
        multiplier *= (1.0 + noisePercent / 100.0);
        multiplier = Math.max(minMultiplier, multiplier);

        return Math.max(1, (int) Math.round(itemData.basePrice() * multiplier));
    }

    public void trackDemand(Connection conn, String itemType) throws SQLException {
        if (itemType == null) return;
        String sql = """
            INSERT INTO seller_price_state (item_type, demand_count, noise_percent, cycle_id)
            VALUES (?, 1, 0, 0)
            ON CONFLICT(item_type) DO UPDATE SET demand_count = demand_count + 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemType);
            ps.executeUpdate();
        }
    }

    public void rollSellerPriceCycle() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            double noiseMin = plugin.getConfig().getDouble("seller.dynamic-pricing.noise-min-percent", -10.0);
            double noiseMax = plugin.getConfig().getDouble("seller.dynamic-pricing.noise-max-percent", 10.0);
            long cycleId = System.currentTimeMillis() / 1000;

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement select = conn.prepareStatement(
                     "SELECT DISTINCT item_type FROM buyer_inventory WHERE sold_at IS NULL AND channel = 'seller' AND item_type IS NOT NULL")) {
                ResultSet rs = select.executeQuery();
                String upsertSql = """
                    INSERT INTO seller_price_state (item_type, demand_count, noise_percent, cycle_id)
                    VALUES (?, 0, ?, ?)
                    ON CONFLICT(item_type) DO UPDATE SET demand_count = 0, noise_percent = EXCLUDED.noise_percent, cycle_id = EXCLUDED.cycle_id
                """;
                try (PreparedStatement upsert = conn.prepareStatement(upsertSql)) {
                    while (rs.next()) {
                        String itemType = rs.getString("item_type");
                        double noise = noiseMin + (noiseMax - noiseMin) * random.nextDouble();
                        upsert.setString(1, itemType);
                        upsert.setDouble(2, noise);
                        upsert.setLong(3, cycleId);
                        upsert.addBatch();
                    }
                    upsert.executeBatch();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Error rolling seller price cycle: " + e.getMessage());
            }
        });
    }
}
