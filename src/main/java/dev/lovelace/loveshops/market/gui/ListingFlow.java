package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager.ListingResult;
import dev.lovelace.loveshops.market.model.TradePoint;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * Putting an item on a shelf: ask for the price, then create the lot. Shared by the shelf menu
 * (item dragged in or shift-clicked from the inventory) and the warehouse ("put on display").
 * The item is already out of the player's hands when this runs, so every way out hands it back
 * through {@code restore} - the price menu closing, a refusal, the part of a stack that was not
 * listed.
 */
final class ListingFlow {

    private ListingFlow() {}

    /**
     * @param item    the whole stack to list (already taken from where it was)
     * @param restore gives an item back to where it came from (inventory or warehouse)
     * @param reopen  shows the next menu after a confirmed price (not after Close / Esc)
     */
    static void sell(LoveShops plugin, Player viewer, TradePoint point, int shelf, ItemStack item,
                     Consumer<ItemStack> restore, Runnable reopen) {
        new PriceGui(plugin, viewer, point.claimId(), item, false, 0L,
                (amount, price) -> {
                    int take = Math.max(1, Math.min(amount, item.getAmount()));
                    ItemStack listed = item.clone();
                    listed.setAmount(take);
                    ListingResult res = plugin.getTradePointManager().addSellListing(viewer, point, shelf, listed, price);
                    if (res == ListingResult.OK) {
                        if (item.getAmount() > take) {
                            ItemStack rest = item.clone();
                            rest.setAmount(item.getAmount() - take);
                            restore.accept(rest);
                        }
                        plugin.getMarketMessages().send(viewer, "listing-added");
                    } else {
                        restore.accept(item);
                        report(plugin, viewer, res);
                    }
                    reopen.run();
                },
                () -> restore.accept(item)
        ).open();
    }

    static void report(LoveShops plugin, Player viewer, ListingResult res) {
        var msg = plugin.getMarketMessages();
        switch (res) {
            case IS_COIN -> msg.send(viewer, "listing-is-coin");
            case FORBIDDEN -> msg.send(viewer, "listing-forbidden");
            case PRICE_LOW -> msg.send(viewer, "listing-price-low");
            case PRICE_HIGH -> msg.send(viewer, "listing-price-high");
            case NO_SLOT -> msg.send(viewer, "listing-no-slot");
            case SLOT_TAKEN -> msg.send(viewer, "listing-slot-taken");
            case NO_SPACE -> msg.send(viewer, "trade-no-space");
            case NO_ITEM -> msg.send(viewer, "trade-no-items");
            default -> msg.send(viewer, "listing-error");
        }
    }
}
