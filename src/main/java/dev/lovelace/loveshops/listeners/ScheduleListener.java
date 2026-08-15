package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

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
            // Retry delivery for auction winners who were offline (or briefly short on
            // funds) when their auction resolved — otherwise those lots would be stuck
            // forever with status='completed' and no item ever handed out.
            plugin.getAuctionManager().deliverPendingWins();
        }, 10, 30, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Give a winner their lot the moment they log back in instead of making them wait
        // for the next 30-second sweep.
        plugin.getAuctionManager().deliverPendingWinsFor(event.getPlayer());
    }
}
