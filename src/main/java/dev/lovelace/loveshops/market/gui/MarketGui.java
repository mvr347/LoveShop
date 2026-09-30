package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Base of every market menu. The menu IS the inventory holder, so the listener recognises it by
 * type (not by title text) and cancels every click and drag: nothing can be moved in or out of a
 * market menu except through the explicit handlers, which is what keeps it dupe-proof.
 */
public abstract class MarketGui implements InventoryHolder {

    protected final LoveShops plugin;
    protected final Player viewer;
    protected Inventory inventory;

    protected MarketGui(LoveShops plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public Player viewer() { return viewer; }

    /** The trade point this menu shows, {@code null} for menus that are not tied to one. */
    public abstract UUID pointId();

    /** (Re)draws the whole menu into {@link #inventory}. */
    public abstract void render();

    /** A click inside the top inventory; the event is already cancelled. */
    public abstract void handleClick(InventoryClickEvent event);

    /** Called when the viewer closes the menu. */
    public void onClose() {
        plugin.getTradePointManager().unregisterViewer(viewer, this);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    protected void show(Inventory inv) {
        this.inventory = inv;
        render();
        plugin.getTradePointManager().registerViewer(this);
        viewer.openInventory(inv);
    }

    protected void refreshClient() {
        viewer.updateInventory();
    }
}
