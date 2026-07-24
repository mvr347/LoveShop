package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        String materialName = item.getType().name();
        int configuredPrice = plugin.getConfig().getInt("prices-config." + materialName, -1);
        if (configuredPrice > 0) {
            return configuredPrice;
        }
        return plugin.getConfig().getInt("buyer.base-price-config.default-price", 100);
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

    public int getReputationBonusPercent(Player player) {
        // Soft integration with LoveBehavior API if available
        int defaultGood = plugin.getConfig().getInt("buyer.reputation-bonus.good-status", 20);
        
        try {
            Class<?> apiClass = Class.forName("dev.lovelace.lovebehavior.api.LoveBehaviorAPI");
            Object apiInstance = Bukkit.getServicesManager().load(apiClass);
            if (apiInstance != null) {
                // Call getReputation(UUID) reflectively
                var method = apiClass.getMethod("getReputation", UUID.class);
                Object repStatus = method.invoke(apiInstance, player.getUniqueId());
                if ("good".equalsIgnoreCase(String.valueOf(repStatus))) {
                    return defaultGood;
                }
            }
        } catch (Exception ignored) {
            // LoveBehavior not installed or different API structure
        }
        return 0;
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
}
