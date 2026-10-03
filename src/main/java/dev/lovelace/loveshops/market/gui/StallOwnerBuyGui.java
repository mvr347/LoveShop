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
 * The owner's buy orders (gui_gen v2.1). A free slot is a plus head and a drop target: an item dropped on it, or
 * shift-clicked in the inventory, is only a sample (it stays with the player) and opens the price
 * menu, then the chat asks how many pieces to buy in total.
 */
public final class StallOwnerBuyGui extends MarketGui {

    private final TradePoint point;
    private final int size;
    private final Map<Integer, StallListing> listingAt = new HashMap<>();
    /** Free order slot -> order index. */
    private final Map<Integer, Integer> freeSlotAt = new HashMap<>();

    public StallOwnerBuyGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
        this.size = MarketLayout.sizeForContent(point.buySlots());
    }

    public void open() {
        show(Bukkit.createInventory(this, size, MessageUtils.parse(viewer, t("gui-buy-title"))));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public boolean ownerMenu() { return true; }

    @Override
    public void render() {
        frame();
        listingAt.clear();
        freeSlotAt.clear();

        List<StallListing> orders = plugin.getTradePointManager().listings(point).stream()
                .filter(l -> l.type() == ListingType.BUY).toList();
        Map<Integer, StallListing> byIndex = new HashMap<>();
        for (StallListing l : orders) byIndex.put(l.slot(), l);

        int slots = point.buySlots();
        int[] content = MarketLayout.centeredSlots(size, slots);
        int totalSlots = Math.min(slots, content.length);
        int used = 0;
        for (int i = 0; i < totalSlots; i++) {
            int slot = content[i];
            StallListing listing = byIndex.get(i);
            if (listing != null) {
                inventory.setItem(slot, orderItem(listing));
                listingAt.put(slot, listing);
                used++;
            } else {
                boolean prevFilled = (i == 0) || (byIndex.containsKey(i - 1) && byIndex.get(i - 1) != null);
                if (prevFilled) {
                    freeSlotAt.put(slot, i);
                } else {
                    int prevSlotNum = i;
                    ItemStack locked = head(HeadTextures.HEAD_DELETE_NO, "<red>Слот №" + (i + 1) + " заблокирован</red>",
                            List.of("", "<gray>Сначала заполните предыдущий слот (Слот №" + prevSlotNum + ")</gray>"));
                    button(slot, locked, e -> MessageUtils.sendMessage(viewer, "<red>Сначала заполните предыдущий слот (Слот №" + prevSlotNum + ")</red>"));
                }
            }
        }

        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-buy-head", "gui-buy-head-lore",
                "used", String.valueOf(used), "total", String.valueOf(point.buySlots()), "level", String.valueOf(point.level())));
        controls(List.of(new Control(tile(HeadTextures.BANKER_WITHDRAW, "gui-buy-collect", "gui-buy-collect-lore"),
                e -> collectAll())));
        footer(() -> new StallTradeMenuGui(plugin, viewer, point).open());
        refreshClient();
    }

    private ItemStack orderItem(StallListing listing) {
        ItemStack item = listing.template().clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        List<String> extra = new ArrayList<>(lines("gui-buy-order-lore",
                "order", String.valueOf(listing.slot() + 1),
                "stock", String.valueOf(listing.stock()),
                "max", String.valueOf(listing.maxAmount()),
                "price", plugin.getMarketStyle().money(listing.price())));
        extra.addAll(lines(listing.stock() > 0 ? "gui-buy-order-collect" : "gui-buy-order-empty", "stock", String.valueOf(listing.stock())));
        extra.addAll(lines("gui-buy-order-actions"));
        for (String line : extra) lore.add(MessageUtils.parse(viewer, line));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ input

    @Override
    public void handleClick(InventoryClickEvent event) {
        if (actions.containsKey(event.getRawSlot())) {
            super.handleClick(event);
            return;
        }
        StallListing listing = listingAt.get(event.getRawSlot());
        if (listing == null) return;
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_RIGHT) {
            TradePointManager.CollectResult res = plugin.getTradePointManager().collectListing(viewer, point, listing.id(), true);
            if (res.ok()) {
                plugin.getMarketMessages().send(viewer, "listing-removed", "count", String.valueOf(res.items()));
            } else {
                plugin.getMarketMessages().send(viewer, "listing-error");
            }
            render();
        } else if (click == ClickType.RIGHT) {
            editPrice(listing);
        } else if (click == ClickType.LEFT) {
            collectItems(listing);
        }
    }

    /** A sample dropped on a free order slot: it is handed straight back, only its kind matters. */
    @Override
    public boolean acceptCursor(int topSlot, ItemStack cursor) {
        Integer index = freeSlotAt.get(topSlot);
        if (index == null) return false;
        if (!sampleAllowed(cursor)) return false;
        // Only the kind of item matters: the sample goes back to the player first, then the menu opens
        // (opening with the stack still on the cursor would return it a second time).
        viewer.setItemOnCursor(null);
        giveBack(cursor.clone());
        openOrder(index, cursor);
        return true;
    }

    @Override
    public void handleBottomClick(InventoryClickEvent event) {
        if (!event.getClick().isShiftClick()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        Integer index = freeSlotAt.values().stream().min(Integer::compare).orElse(null);
        if (index == null) {
            plugin.getMarketMessages().send(viewer, "listing-no-slot");
            return;
        }
        if (sampleAllowed(clicked)) openOrder(index, clicked.clone());
    }

    private boolean sampleAllowed(ItemStack sample) {
        ListingResult check = plugin.getTradePointManager().previewItem(viewer, point, sample);
        if (check != ListingResult.OK) {
            ListingFlow.report(plugin, viewer, check);
            return false;
        }
        return true;
    }

    private void openOrder(int index, ItemStack sample) {
        ItemStack template = sample.clone();
        template.setAmount(1);
        new PriceGui(plugin, viewer, point.claimId(), template, true, 0L,
                (amount, price) -> promptNumber("prompt-max", 1, 1000,
                        () -> new StallOwnerBuyGui(plugin, viewer, point).open(),
                        total -> {
                            ListingResult res = plugin.getTradePointManager().addBuyListing(viewer, point, index, template, price, (int) total);
                            if (res == ListingResult.OK) {
                                plugin.getMarketMessages().send(viewer, "listing-added");
                            } else {
                                ListingFlow.report(plugin, viewer, res);
                            }
                            new StallOwnerBuyGui(plugin, viewer, point).open();
                        }),
                () -> { }
        ).open();
    }

    private void collectAll() {
        List<StallListing> orders = plugin.getTradePointManager().listings(point).stream()
                .filter(l -> l.type() == ListingType.BUY && l.stock() > 0).toList();
        if (orders.isEmpty()) {
            plugin.getMarketMessages().send(viewer, "buy-nothing-collected");
            return;
        }
        for (StallListing l : orders) {
            plugin.getTradePointManager().collectListing(viewer, point, l.id(), false);
        }
        plugin.getMarketMessages().send(viewer, "buy-collected-all");
        render();
    }

    private void collectItems(StallListing listing) {
        if (listing.stock() <= 0) {
            plugin.getMarketMessages().send(viewer, "buy-nothing-collected");
            return;
        }
        TradePointManager.CollectResult res = plugin.getTradePointManager().collectListing(viewer, point, listing.id(), false);
        if (res.ok()) {
            plugin.getMarketMessages().send(viewer, "buy-collected", "count", String.valueOf(res.items()));
        } else {
            plugin.getMarketMessages().send(viewer, "listing-error");
        }
        render();
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
                    new StallOwnerBuyGui(plugin, viewer, point).open();
                },
                () -> { }
        ).open();
    }
}
