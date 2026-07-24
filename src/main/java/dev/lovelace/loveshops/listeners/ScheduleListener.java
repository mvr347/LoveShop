package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

import java.util.concurrent.TimeUnit;

public class ScheduleListener implements Listener {

    private final LoveShops plugin;

    public ScheduleListener(LoveShops plugin) {
        this.plugin = plugin;
        startAuctionTimer();
    }

    private void startAuctionTimer() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            // Check completed auctions every 60 seconds
            plugin.getAuctionManager().checkAndCompleteAuctions();
            plugin.getSellerManager().checkSellerStatus();
        }, 10, 30, TimeUnit.SECONDS);
    }
}
