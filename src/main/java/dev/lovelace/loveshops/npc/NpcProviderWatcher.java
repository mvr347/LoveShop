package dev.lovelace.loveshops.npc;

import dev.lovelace.lovecore.api.npc.NpcProvider;
import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;

/**
 * Calls back when the configured NPC provider registers. Providers come up late (LoveEssential after the server
 * has loaded), after LoveShops has already tried to show its NPCs, so the work is repeated once it appears.
 */
public final class NpcProviderWatcher implements Listener {

    private final LoveShops plugin;
    private final Runnable onReady;

    public NpcProviderWatcher(LoveShops plugin, Runnable onReady) {
        this.plugin = plugin;
        this.onReady = onReady;
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent event) {
        if (event.getProvider().getService() != NpcProvider.class) return;
        if (!(event.getProvider().getProvider() instanceof NpcProvider provider)) return;
        if (!plugin.getNpcBridge().ownsProvider(provider.providerId())) return;
        // One tick later: the registration is complete and any other plugin's listeners have run.
        Bukkit.getScheduler().runTask(plugin, onReady);
    }
}
