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

import java.util.List;
import java.util.Set;

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
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null) return;

        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            event.setCancelled(true);
            // An item on the cursor dropped on a slot: let the menu take it (shelf, storage, price).
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.getType().isAir()
                    && gui.acceptCursor(event.getRawSlot(), cursor.clone())) {
                player.setItemOnCursor(null);
                player.updateInventory();
                return;
            }
            gui.handleClick(event);
        } else if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            if (event.isShiftClick()) {
                event.setCancelled(true);
                gui.handleBottomClick(event);
            } else if (!isPlainBottomClick(event)) {
                event.setCancelled(true);
            }
            // Plain bottom click (picking up / putting down in own inventory) is NOT cancelled,
            // allowing the item to get onto the cursor to be dropped on the showcase menu.
        }
    }

    private static boolean isPlainBottomClick(InventoryClickEvent event) {
        if (event.getRawSlot() < 0) return false;
        return switch (event.getAction()) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, PLACE_ALL, PLACE_ONE, PLACE_SOME,
                 SWAP_WITH_CURSOR, HOTBAR_SWAP, NOTHING -> true;
            default -> false;
        };
    }

    /**
     * "Dragging" an item with the mouse. A drag from the player's inventory into the menu always covers
     * several slots (the emptied source slot of the inventory, usually some in between and the target), so
     * the menu slots it touched are tried in order and the first one that takes the item wins - the same
     * way the older caravan menus treat a drag. A drag that touched no menu slot is only cancelled
     * (the menu cannot split a stack).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MarketGui gui)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        List<Integer> targets = dragTargets(event.getRawSlots(), event.getView().getTopInventory().getSize());
        if (targets.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!(player.getOpenInventory().getTopInventory().getHolder() == gui)) return;
            ItemStack onCursor = player.getItemOnCursor();
            if (onCursor == null || onCursor.getType().isAir()) return;
            for (int slot : targets) {
                if (gui.acceptCursor(slot, onCursor.clone())) {
                    player.setItemOnCursor(null);
                    player.updateInventory();
                    return;
                }
            }
        });
    }

    /** The menu (top inventory) slots a drag touched, lowest first; slots of the player's own inventory are dropped. */
    static List<Integer> dragTargets(Set<Integer> rawSlots, int topSize) {
        return rawSlots.stream().filter(slot -> slot >= 0 && slot < topSize).sorted().toList();
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
