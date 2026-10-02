package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.gui.MarketGui;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Every click and drag in a market menu is cancelled, in the top inventory AND in the player's own
 * one (shift-click, number keys, double-click gathering all move items across the two); only the
 * menu's explicit handlers act. Menus are recognised by their holder type, never by title.
 */
public final class MarketGuiListener implements Listener {

    private final LoveShops plugin;

    public MarketGuiListener(LoveShops plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketGui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            gui.handleClick(event);
        } else if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            gui.handleBottomClick(event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketGui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        // Let storage/sell GUIs accept drags into empty content slots.
        if (gui.handleDrag(event)) {
            // handled; stay cancelled so vanilla does not move items twice
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MarketGui gui) {
            gui.onClose();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // After LoveAuth's login flow and the join messages, so the notice is not buried.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) plugin.getTradePointManager().deliverNotices(player);
        }, 60L);
    }
}
