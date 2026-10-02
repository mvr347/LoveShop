package dev.lovelace.loveshops.listeners;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class CaravanPlayerListener implements Listener {

    private final LoveShops plugin;

    public CaravanPlayerListener(LoveShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.getCommissionManager() != null && plugin.getCommissionManager().isEnabled()) {
            org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    plugin.getCommissionManager().claimPendingPayouts(player);
                }
            }, 60L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!event.getAction().isRightClick()) return;
        org.bukkit.inventory.ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) return;

        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, dev.lovelace.loveshops.managers.LostCaravanManager.CRATE_TYPE_KEY);
        if (item.getItemMeta().getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.STRING)) {
            event.setCancelled(true);
            if (plugin.getLostCaravanManager() != null) {
                plugin.getLostCaravanManager().openCrateItem(event.getPlayer(), item);
            }
        }
    }
}
