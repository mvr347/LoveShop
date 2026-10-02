package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

import java.util.concurrent.TimeUnit;

public class ScheduleListener implements Listener {

    private final LoveShops plugin;

    public ScheduleListener(LoveShops plugin) {
        this.plugin = plugin;
        startScheduleTimer();
    }

    private void startScheduleTimer() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            plugin.getWandererManager().checkWandererStatus();
        }, 10, 30, TimeUnit.SECONDS);
    }
}
