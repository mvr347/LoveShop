package dev.lovelace.loveshops.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class GuiBuyerItemClickEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ItemStack item;
    private final int calculatedPrice;

    public GuiBuyerItemClickEvent(Player player, ItemStack item, int calculatedPrice) {
        this.player = player;
        this.item = item;
        this.calculatedPrice = calculatedPrice;
    }

    public Player getPlayer() { return player; }
    public ItemStack getItem() { return item; }
    public int getCalculatedPrice() { return calculatedPrice; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
