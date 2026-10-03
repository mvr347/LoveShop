package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.StorageItem;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The point's warehouse (54 slots, gui_gen v2.1, pagination on slots 36 / 44). Items go in by
 * dropping them on the menu (mouse drag or cursor) or by shift-clicking them in the inventory;
 * a click takes a stack out, shift-click puts it on a shelf. Capacity grows with the point's level.
 */
public final class StallStorageGui extends MarketGui {

    private static final int SIZE = 54;

    private final TradePoint point;
    private final Map<Integer, StorageItem> itemAt = new HashMap<>();

    public StallStorageGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-storage-title"))));
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
        itemAt.clear();

        int capacity = plugin.getTradePointManager().getStorageCapacity(point);
        List<StorageItem> stored = plugin.getTradePointManager().getStorage(point);

        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-storage-head", "gui-storage-head-lore",
                "stored", String.valueOf(stored.size()), "capacity", String.valueOf(capacity),
                "level", String.valueOf(point.level())));
        // "Collect all" is the footer's extra button (slot 51 of 54), not a header control.
        button(MarketLayout.extraSlot(SIZE), tile(HeadTextures.BANKER_WITHDRAW, "gui-storage-collect", "gui-storage-collect-lore"),
                e -> collectAll());

        if (stored.isEmpty()) emptyCard("gui-storage-empty-lore");
        int[] content = MarketLayout.contentSlots(SIZE);
        int perPage = content.length;
        int totalPages = Math.max(1, (int) Math.ceil((double) stored.size() / perPage));
        int start = pager(totalPages) * perPage;
        for (int i = 0; i < perPage && start + i < stored.size(); i++) {
            StorageItem si = stored.get(start + i);
            inventory.setItem(content[i], storageItem(si));
            itemAt.put(content[i], si);
        }

        footer(() -> new StallOwnerGui(plugin, viewer, point).open());
        refreshClient();
    }

    private ItemStack storageItem(StorageItem si) {
        ItemStack item = si.item().clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        for (String line : lines("gui-storage-item-lore")) lore.add(MessageUtils.parse(viewer, line));
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
        StorageItem si = itemAt.get(event.getRawSlot());
        if (si == null) return;
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            listFromStorage(si);
        } else if (click == ClickType.LEFT) {
            takeToInventory(si);
        }
    }

    /** Anything dropped on the work zone (not on a button) goes to the warehouse. */
    @Override
    public boolean acceptCursor(int topSlot, ItemStack cursor) {
        if (topSlot < MarketLayout.workStart(SIZE) || topSlot >= SIZE - 9 || actions.containsKey(topSlot)) return false;
        return deposit(cursor);
    }

    @Override
    public void handleBottomClick(InventoryClickEvent event) {
        if (!event.getClick().isShiftClick()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        if (freeSlot(plugin.getTradePointManager().getStorage(point)) < 0) {
            plugin.getMarketMessages().send(viewer, "storage-full");
            return;
        }
        ItemStack taken = takeClicked(event);
        if (taken != null && !deposit(taken)) giveBack(taken);
    }

    /** Puts a stack into the first free warehouse slot; {@code false} (nothing changed) when full or refused. */
    private boolean deposit(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        var check = plugin.getTradePointManager().previewItem(viewer, point, item);
        if (check == TradePointManager.ListingResult.IS_COIN || check == TradePointManager.ListingResult.FORBIDDEN) {
            ListingFlow.report(plugin, viewer, check);
            return false;
        }
        int slot = freeSlot(plugin.getTradePointManager().getStorage(point));
        if (slot < 0) {
            plugin.getMarketMessages().send(viewer, "storage-full");
            return false;
        }
        if (!plugin.getTradePointManager().putStorageItem(point, slot, item.clone())) {
            plugin.getMarketMessages().send(viewer, "db-error");
            return false;
        }
        plugin.getMarketMessages().send(viewer, "storage-deposited");
        render();
        return true;
    }

    /** First free slot below the capacity, {@code -1} when the warehouse is full. */
    private int freeSlot(List<StorageItem> stored) {
        int capacity = plugin.getTradePointManager().getStorageCapacity(point);
        Set<Integer> taken = new HashSet<>();
        for (StorageItem si : stored) taken.add(si.slot());
        for (int i = 0; i < capacity; i++) if (!taken.contains(i)) return i;
        return -1;
    }

    private void takeToInventory(StorageItem si) {
        ItemStack taken = plugin.getTradePointManager().takeStorageItem(point, si.slot());
        if (taken == null) return;
        var rest = viewer.getInventory().addItem(taken);
        if (!rest.isEmpty()) {
            for (ItemStack remainder : rest.values()) {
                plugin.getTradePointManager().putStorageItem(point, si.slot(), remainder);
            }
            plugin.getMarketMessages().send(viewer, "storage-inventory-full");
        }
        render();
    }

    private void collectAll() {
        List<StorageItem> stored = plugin.getTradePointManager().getStorage(point);
        if (stored.isEmpty()) {
            plugin.getMarketMessages().send(viewer, "storage-empty");
            return;
        }
        int collected = 0;
        for (StorageItem si : new ArrayList<>(stored)) {
            ItemStack taken = plugin.getTradePointManager().takeStorageItem(point, si.slot());
            if (taken == null) continue;
            var rest = viewer.getInventory().addItem(taken);
            if (!rest.isEmpty()) {
                for (ItemStack remainder : rest.values()) {
                    plugin.getTradePointManager().putStorageItem(point, si.slot(), remainder);
                }
                plugin.getMarketMessages().send(viewer, "storage-inventory-full");
                break;
            }
            collected++;
        }
        if (collected > 0) plugin.getMarketMessages().send(viewer, "storage-collected", "count", String.valueOf(collected));
        render();
    }

    private void listFromStorage(StorageItem si) {
        int shelf = freeSellShelf();
        if (shelf < 0) {
            plugin.getMarketMessages().send(viewer, "listing-no-slot");
            return;
        }
        ItemStack taken = plugin.getTradePointManager().takeStorageItem(point, si.slot());
        if (taken == null) return;
        ListingFlow.sell(plugin, viewer, point, shelf, taken, this::backToStorage,
                () -> new StallStorageGui(plugin, viewer, point).open());
    }

    /** Returns an item to the warehouse, or to the player when the warehouse got full meanwhile. */
    private void backToStorage(ItemStack item) {
        int slot = freeSlot(plugin.getTradePointManager().getStorage(point));
        if (slot < 0 || !plugin.getTradePointManager().putStorageItem(point, slot, item)) giveBack(item);
    }

    private int freeSellShelf() {
        Set<Integer> taken = new HashSet<>();
        for (StallListing l : plugin.getTradePointManager().listings(point)) {
            if (l.type() == ListingType.SELL && l.stock() > 0) taken.add(l.slot());
        }
        for (int i = 0; i < point.sellSlots(); i++) if (!taken.contains(i)) return i;
        return -1;
    }
}
