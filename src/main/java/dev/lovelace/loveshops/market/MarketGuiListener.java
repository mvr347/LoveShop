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
import org.bukkit.inventory.ItemStack;

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
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            // An item on the cursor dropped on a slot: let the menu take it (shelf, storage, price).
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()
                    && gui.acceptCursor(event.getRawSlot(), cursor.clone())) {
                player.setItemOnCursor(null);
                return;
            }
            gui.handleClick(event);
        } else if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            gui.handleBottomClick(event);
        }
    }

    /**
     * "Dragging" an item with the mouse: pressing, moving and releasing is a drag event even over a
     * single slot, not a click, so it has to be treated as a drop on that slot. A drag over several
     * slots is only cancelled (the menu cannot split a stack).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketGui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().size() != 1) return;
        int slot = event.getRawSlots().iterator().next();
        if (slot >= topSize) return;
        ItemStack cursor = event.getOldCursor();
        if (cursor.getType().isAir()) return;
        ItemStack offered = cursor.clone();
        // The cursor of a cancelled drag is restored by the client/server after this tick, so it is
        // cleared on the next one - but only if it still holds what was dragged (no duplication).
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!(player.getOpenInventory().getTopInventory().getHolder() == gui)) return;
            ItemStack onCursor = player.getItemOnCursor();
            if (!onCursor.isSimilar(offered) || onCursor.getAmount() != offered.getAmount()) return;
            if (gui.acceptCursor(slot, offered)) {
                player.setItemOnCursor(null);
                player.updateInventory();
            }
        });
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
