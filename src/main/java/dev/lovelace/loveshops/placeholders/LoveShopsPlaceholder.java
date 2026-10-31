package dev.lovelace.loveshops.placeholders;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.ShopStatsCache;
import dev.lovelace.loveshops.utils.TimeUtils;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LoveShopsPlaceholder extends PlaceholderExpansion {

    private final LoveShops plugin;
    private final ShopStatsCache stats;

    public LoveShopsPlaceholder(LoveShops plugin) {
        this.plugin = plugin;
        this.stats = new ShopStatsCache(
                ShopStatsCache.databaseSource(plugin.getDatabaseManager()),
                plugin.getDatabaseManager()::runAsync,
                System::currentTimeMillis,
                60_000L,
                plugin.getLogger());
    }

    @Override
    public @NotNull String getIdentifier() {
        return "loveshops";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Lovelace";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("wanderer_active")) {
            return String.valueOf(plugin.getWandererManager().isWandererActive());
        }

        if (params.equalsIgnoreCase("wanderer_arrival")) {
            return plugin.getWandererManager().getNextArrivalText();
        }

        // Cache only: onRequest runs on the main thread and must not touch the DB or wait on a future.
        if (params.equalsIgnoreCase("wanderer_deal_status") && player != null) {
            return plugin.getWandererManager().getDealCache()
                    .status(player.getUniqueId(), System.currentTimeMillis() / 1000);
        }

        if (params.equalsIgnoreCase("wanderer_deal_time") && player != null) {
            return plugin.getWandererManager().getDealCache().get(player.getUniqueId())
                    .map(d -> TimeUtils.formatRemainingTime(d.remainingSeconds())).orElse("0с");
        }

        // Profile statistics: trade points and the trader's numbers (database values come from the cache).
        if (player != null) {
            String shopValue = shopStat(player, params.toLowerCase(java.util.Locale.ROOT));
            if (shopValue != null) return shopValue;
        }

        if (params.startsWith("item_") && params.endsWith("_price")) {
            String matName = params.substring(5, params.length() - 6).toUpperCase();
            try {
                Material mat = Material.valueOf(matName);
                return String.valueOf(plugin.getPriceCalculator().getBasePrice(new ItemStack(mat)));
            } catch (Exception e) {
                return "0";
            }
        }

        return null;
    }

    /** One of the trade-point or trader values, or {@code null} when the name is not one of them. */
    private @Nullable String shopStat(OfflinePlayer player, String key) {
        switch (key) {
            case "points", "sales_total", "revenue_total", "rating", "trader_level", "total_sold", "total_bought" -> {
                return format(key, stats.get(player.getUniqueId()).orElse(ShopStatsCache.Values.EMPTY));
            }
            case "banker_fee" -> {
                return String.valueOf(plugin.getBankerManager().getFeePercent(player.getUniqueId()));
            }
            case "buyer_bonus" -> {
                // The bonus follows the live reputation tier, so it exists only while the player is online.
                var online = player.getPlayer();
                return online == null ? "" : String.valueOf(plugin.getPriceCalculator().getReputationBonusPercent(online));
            }
            default -> {
                return null;
            }
        }
    }

    static String format(String key, ShopStatsCache.Values v) {
        return switch (key) {
            case "points" -> String.valueOf(v.points());
            case "sales_total" -> String.valueOf(v.salesTotal());
            case "revenue_total" -> String.valueOf(v.revenueTotal());
            case "rating" -> v.ratings() == 0 ? "—" : String.format(java.util.Locale.ROOT, "%.1f", v.ratingStars());
            case "trader_level" -> String.valueOf(v.traderLevel());
            case "total_sold" -> String.valueOf(v.totalSold());
            case "total_bought" -> String.valueOf(v.totalBought());
            default -> "";
        };
    }
}
