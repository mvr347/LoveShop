package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.TradePointManager.ListingResult;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The owner's shelves (gui_gen v2.1). Free shelves are real empty slots: an item dropped on one
 * (dragged with the mouse, or placed from the cursor) or shift-clicked in the inventory opens the
 * price menu. The menu is 27 or 36 slots depending on how many shelves the point has.
 */
public final class StallOwnerSellGui extends MarketGui {

    private final TradePoint point;
    private final int size;
    private final Map<Integer, StallListing> listingAt = new HashMap<>();
    /** Free shelf slot -> shelf index. */
    private final Map<Integer, Integer> freeShelfAt = new HashMap<>();

    public StallOwnerSellGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
        this.size = MarketLayout.sizeForContent(point.sellSlots());
    }

    public void open() {
        show(Bukkit.createInventory(this, size, MessageUtils.parse(viewer, t("gui-sell-title"))));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        frame();
        listingAt.clear();
        freeShelfAt.clear();

        List<StallListing> sells = plugin.getTradePointManager().listings(point).stream()
                .filter(l -> l.type() == ListingType.SELL).toList();
        Map<Integer, StallListing> byShelf = new HashMap<>();
        for (StallListing l : sells) byShelf.put(l.slot(), l);

        int[] content = MarketLayout.contentSlots(size);
        int shelves = Math.min(point.sellSlots(), content.length);
        int used = 0;
        for (int shelf = 0; shelf < shelves; shelf++) {
            int slot = content[shelf];
            StallListing listing = byShelf.get(shelf);
            if (listing != null && listing.stock() > 0) {
                inventory.setItem(slot, shelfItem(listing));
                listingAt.put(slot, listing);
                used++;
            } else {
                freeShelfAt.put(slot, shelf);
            }
        }

        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-sell-head", "gui-sell-head-lore",
                "used", String.valueOf(used), "total", String.valueOf(point.sellSlots()), "level", String.valueOf(point.level())));
        button(MarketLayout.extraSlot(size), tile(HeadTextures.BANKER_DEPOSIT, "gui-sell-storage", "gui-sell-storage-lore"),
                e -> new StallStorageGui(plugin, viewer, point).open());
        footer(() -> new StallTradeMenuGui(plugin, viewer, point).open());
        refreshClient();
    }

    private ItemStack shelfItem(StallListing listing) {
        ItemStack item = listing.template().clone();
        item.setAmount(Math.max(1, Math.min(listing.stock(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        for (String line : lines("gui-sell-shelf-lore",
                "shelf", String.valueOf(listing.slot() + 1),
                "stock", String.valueOf(listing.stock()),
                "price", plugin.getMarketStyle().money(listing.price()))) {
            lore.add(MessageUtils.parse(viewer, line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ input

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (actions.containsKey(slot)) {
            super.handleClick(event);
            return;
        }
        StallListing listing = listingAt.get(slot);
        if (listing == null) return;
        ClickType click = event.getClick();
        if (click == ClickType.RIGHT) {
            takeDown(listing);
        } else if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            addStockFromHand(listing);
        } else if (click == ClickType.LEFT) {
            editPrice(listing);
        }
    }

    /** An item dropped on a free shelf: open the price menu for it. */
    @Override
    public boolean acceptCursor(int topSlot, ItemStack cursor) {
        Integer shelf = freeShelfAt.get(topSlot);
        if (shelf == null) return false;
        ListingResult check = plugin.getTradePointManager().previewItem(viewer, point, cursor);
        if (check != ListingResult.OK) {
            ListingFlow.report(plugin, viewer, check);
            return false;
        }
        // The stack lives only in the price menu from now on; the cursor is emptied before that menu
        // opens, otherwise the server would hand the same stack back when this menu closes.
        viewer.setItemOnCursor(null);
        startListing(shelf, cursor);
        return true;
    }

    /** Shift-click on an item in the player's own inventory: put it on the first free shelf. */
    @Override
    public void handleBottomClick(InventoryClickEvent event) {
        if (!event.getClick().isShiftClick()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        Integer shelf = freeShelfAt.values().stream().min(Integer::compare).orElse(null);
        if (shelf == null) {
            plugin.getMarketMessages().send(viewer, "listing-no-slot");
            return;
        }
        ListingResult check = plugin.getTradePointManager().previewItem(viewer, point, clicked);
        if (check != ListingResult.OK) {
            ListingFlow.report(plugin, viewer, check);
            return;
        }
        ItemStack taken = takeClicked(event);
        if (taken != null) startListing(shelf, taken);
    }

    /** Opens the price menu for a stack that is no longer anywhere else (cursor emptied / taken from the inventory). */
    private void startListing(int shelf, ItemStack item) {
        ListingFlow.sell(plugin, viewer, point, shelf, item.clone(), this::giveBack,
                () -> new StallOwnerSellGui(plugin, viewer, point).open());
    }

    private void editPrice(StallListing listing) {
        new PriceGui(plugin, viewer, point.claimId(), listing.template(), true, listing.price(),
                (amount, newPrice) -> {
                    ListingResult res = plugin.getTradePointManager().changePrice(viewer, point, listing.id(), newPrice);
                    if (res == ListingResult.OK) {
                        plugin.getMarketMessages().send(viewer, "listing-price-changed");
                    } else {
                        ListingFlow.report(plugin, viewer, res);
                    }
                    new StallOwnerSellGui(plugin, viewer, point).open();
                },
                () -> { }
        ).open();
    }

    private void takeDown(StallListing listing) {
        TradePointManager.CollectResult res = plugin.getTradePointManager().collectListing(viewer, point, listing.id(), true);
        plugin.getMarketMessages().send(viewer, res.ok() ? "listing-removed" : "listing-error");
        render();
    }

    private void addStockFromHand(StallListing listing) {
        ListingResult res = plugin.getTradePointManager().addStock(viewer, point, listing.id());
        if (res == ListingResult.OK) {
            plugin.getMarketMessages().send(viewer, "listing-stock-added");
        } else {
            ListingFlow.report(plugin, viewer, res);
        }
        render();
    }
}
