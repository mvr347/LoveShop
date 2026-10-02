package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
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
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class PriceCalculator {

    private final LoveShops plugin;
    private final Random random = new Random();

    public PriceCalculator(LoveShops plugin) {
        this.plugin = plugin;
    }

    public int getBasePrice(ItemStack item) {
        if (item == null) return 0;

        // Клановые артефакты ценятся не по материалу, а по типу: иначе боевой рог
        // ушёл бы по цене обычного предмета того же материала.
        String artifactType = artifactTypeOf(item);
        if (artifactType != null) {
            int artifactPrice = plugin.getConfig().getInt("prices-config.artifacts." + artifactType, -1);
            if (artifactPrice > 0) {
                return artifactPrice;
            }
            return plugin.getConfig().getInt("prices-config.artifacts.default-price", 2500);
        }

        String materialName = item.getType().name();

        int configuredPrice = plugin.getPricesManager().getCommonPrice(materialName, -1);
        if (configuredPrice > 0) {
            return configuredPrice;
        }
        return plugin.getConfig().getInt("buyer.base-price-config.default-price", 1);
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
        return getRandomVariancePercent(null);
    }

    public double getRandomVariancePercent(String itemType) {
        double min = plugin.getConfig().getDouble("buyer.price-variance.min-percent", -40.0);
        double max = plugin.getConfig().getDouble("buyer.price-variance.max-percent", 10.0);
        int durationMinutes = plugin.getConfig().getInt("buyer.price-variance.duration-minutes", 60);
        if (durationMinutes <= 0) {
            return min + (max - min) * random.nextDouble();
        }

        long durationMillis = durationMinutes * 60L * 1000L;
        long timeBucket = System.currentTimeMillis() / durationMillis;

        long seed = timeBucket * 1000003L;
        if (itemType != null) {
            seed ^= (long) itemType.hashCode() * 31L;
        }
        Random bucketRandom = new Random(seed);
        return min + (max - min) * bucketRandom.nextDouble();
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

    /**
     * Надбавка или штраф к цене скупки по репутации игрока.
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
        double variancePercent = getRandomVariancePercent(item.getType().name());
        double penaltyPercent = getPenaltyPercent(player.getUniqueId(), item.getType().name());
        int repBonusPercent = getReputationBonusPercent(player);
        int dailyBonusPercent = 0;

        double multiplier = 1.0;
        multiplier += (variancePercent / 100.0);
        multiplier -= (penaltyPercent / 100.0);
        multiplier += (repBonusPercent / 100.0);
        multiplier += (dailyBonusPercent / 100.0);

        int singleUnitPrice = (int) Math.round(basePrice * Math.max(0.1, multiplier));
        // /loveshopsadmin price mult buyer <%>: one lever for the whole buyer's price list.
        singleUnitPrice = plugin.getPricesManager().applyMultiplier("buyer", singleUnitPrice);
        return Math.max(1, singleUnitPrice * item.getAmount());
    }

    // ===== merchant-tax: flat economy-sink cut on LoveShop's own NPCs (NOT Wanderer) =====

    /**
     * 2026-09-24 (owner request): a flat percentage cut applied on top of everything else at
     * the final price for the Buyer NPC (Скупщик) and WarMerchant — see {@code merchant-tax}
     * in config.yml. Wanderer is explicitly excluded per the owner's instruction.
     */
    public long applyMerchantTaxToPayout(long basePayout) {
        return Math.max(0, Math.round(basePayout * (1.0 - merchantTaxRate())));
    }

    /** See {@link #applyMerchantTaxToPayout(long)} — the purchase-side counterpart (cost up, not payout down). */
    public long applyMerchantTaxToCost(long baseCost) {
        return Math.round(baseCost * (1.0 + merchantTaxRate()));
    }

    private double merchantTaxRate() {
        if (!plugin.getConfig().getBoolean("merchant-tax.enabled", true)) {
            return 0.0;
        }
        double percent = plugin.getConfig().getDouble("merchant-tax.percent", 7.0);
        return Math.max(0.0, Math.min(100.0, percent)) / 100.0;
    }
}
