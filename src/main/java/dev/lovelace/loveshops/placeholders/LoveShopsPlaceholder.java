package dev.lovelace.loveshops.placeholders;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.TimeUtils;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LoveShopsPlaceholder extends PlaceholderExpansion {

    private final LoveShops plugin;

    public LoveShopsPlaceholder(LoveShops plugin) {
        this.plugin = plugin;
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
        if (params.equalsIgnoreCase("seller_arrival")) {
            String day = plugin.getConfig().getString("seller.arrival-day", "SUNDAY");
            String time = plugin.getConfig().getString("seller.arrival-time", "10:00");
            return TimeUtils.getNextArrivalText(day, time);
        }

        if (params.equalsIgnoreCase("seller_active")) {
            return String.valueOf(plugin.getSellerManager().isSellerActive());
        }

        if (params.equalsIgnoreCase("auction_count")) {
            try {
                return String.valueOf(plugin.getAuctionManager().getActiveAuctions().get().size());
            } catch (Exception e) {
                return "0";
            }
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
}
