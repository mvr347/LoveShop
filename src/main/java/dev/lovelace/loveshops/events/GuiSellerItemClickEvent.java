package dev.lovelace.loveshops.events;

import dev.lovelace.loveshops.models.BuyerItemData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class GuiSellerItemClickEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final BuyerItemData itemData;
    private final int price;

    public GuiSellerItemClickEvent(Player player, BuyerItemData itemData, int price) {
        this.player = player;
        this.itemData = itemData;
        this.price = price;
    }

    public Player getPlayer() { return player; }
    public BuyerItemData getItemData() { return itemData; }
    public int getPrice() { return price; }

    @Override
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
