package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.TradePointManager.ListingResult;
import dev.lovelace.loveshops.market.model.StorageItem;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * Warehouse Storage GUI for trade point.
 * Capacity scales with level (UpgradeMath.storageCapacity).
 * Supports depositing/withdrawing stacks, collecting all to inventory, and "Выставить на витрину" via PriceGui.
 */
public final class StallStorageGui extends MarketGui {

    private static final int SIZE = 54;

    private final TradePoint point;
    private int page = 0;
    private final Map<Integer, StorageItem> itemAt = new HashMap<>();

    public StallStorageGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<gold>Торговая точка — склад</gold>");
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        MarketLayout.frame(inventory);
        itemAt.clear();

        int capacity = plugin.getTradePointManager().getStorageCapacity(point);
        List<StorageItem> stored = plugin.getTradePointManager().getStorage(point);

        // Header
        inventory.setItem(0, headerHead(stored.size(), capacity));
        inventory.setItem(4, collectAllItem());

        // Work zone
        int[] content = MarketLayout.contentSlots(SIZE);
        int perPage = content.length;
        int totalPages = Math.max(1, (int) Math.ceil((double) stored.size() / perPage));
        if (page >= totalPages) page = totalPages - 1;

        int start = page * perPage;
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx < stored.size()) {
                StorageItem si = stored.get(idx);
                int slot = content[i];
                inventory.setItem(slot, formatStorageItem(si));
                itemAt.put(slot, si);
            }
        }

        // Footer
        if (page > 0) {
            inventory.setItem(MarketLayout.extraSlot(SIZE) - 1, head(HeadTextures.BUTTON_ARROW_LEFT, "<yellow>Предыдущая страница</yellow>",
                    List.of("", "<gray>Страница " + page + " / " + totalPages + "</gray>", "<yellow>ЛКМ </yellow><gray>— назад</gray>")));
        }
        if (page < totalPages - 1) {
            inventory.setItem(MarketLayout.extraSlot(SIZE), head(HeadTextures.BUTTON_ARROW_RIGHT, "<yellow>Следующая страница</yellow>",
                    List.of("", "<gray>Страница " + (page + 2) + " / " + totalPages + "</gray>", "<yellow>ЛКМ </yellow><gray>— вперёд</gray>")));
        }

        inventory.setItem(MarketLayout.backSlot(SIZE), head(HeadTextures.BUTTON_BACK, "<yellow>Назад</yellow>",
                List.of("", "<gray>В главное меню точки</gray>", "<yellow>ЛКМ </yellow><gray>— вернуться</gray>")));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        refreshClient();
    }

    private ItemStack headerHead(int stored, int capacity) {
        return head(HeadTextures.BANKER_INFO, "<gold>Склад торговой точки</gold>", List.of(
                "",
                "<gray>Занято стеков: <white>" + stored + "</white> / <white>" + capacity + "</white></gray>",
                "<gray>Уровень точки: <white>" + point.level() + "</white></gray>",
                "",
                "<gray>Кликните по предмету в инвентаре,</gray>",
                "<gray>чтобы положить его на склад.</gray>"
        ));
    }

    private ItemStack collectAllItem() {
        return icon(Material.HOPPER, "<green>Собрать всё в инвентарь</green>", List.of(
                "",
                "<gray>Забирает все предметы со склада в ваш</gray>",
                "<gray>инвентарь (сколько поместится).</gray>",
                "",
                "<green>ЛКМ </green><gray>— забрать всё</gray>"
        ));
    }

    private ItemStack formatStorageItem(StorageItem si) {
        ItemStack item = si.item().clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— забрать стек в инвентарь</gray>"));
        lore.add(MessageUtils.parse(viewer, "<yellow>Shift+ЛКМ </yellow><gray>— выставить на витрину</gray>"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageUtils.parse(viewer, name));
        List<Component> compLore = new ArrayList<>();
        for (String line : lore) compLore.add(MessageUtils.parse(viewer, line));
        meta.lore(compLore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        ClickType click = event.getClick();

        if (rawSlot == MarketLayout.closeSlot(SIZE)) {
            viewer.closeInventory();
            return;
        }

        if (rawSlot == MarketLayout.backSlot(SIZE)) {
            new StallOwnerGui(plugin, viewer, point).open();
            return;
        }

        if (rawSlot == 4) {
            collectAll();
            return;
        }

        int prevSlot = MarketLayout.extraSlot(SIZE) - 1;
        int nextSlot = MarketLayout.extraSlot(SIZE);
        if (rawSlot == prevSlot && page > 0) {
            page--;
            render();
            return;
        }
        if (rawSlot == nextSlot) {
            page++;
            render();
            return;
        }

        // Top inventory item clicked (Storage -> Take or Sell)
        if (rawSlot >= 0 && rawSlot < SIZE) {
            StorageItem si = itemAt.get(rawSlot);
            if (si != null) {
                if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                    // "Выставить на витрину" via PriceGui
                    listFromStorage(si);
                } else if (click == ClickType.LEFT) {
                    // Take stack to player inventory
                    takeToInventory(si);
                }
            }
            return;
        }

        // Bottom inventory item clicked (Player inventory -> Deposit to storage)
        if (rawSlot >= SIZE) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
                depositToStorage(clicked);
            }
        }
    }

    private void takeToInventory(StorageItem si) {
        ItemStack taken = plugin.getTradePointManager().takeStorageItem(point, si.slot());
        if (taken == null) return;
        var rem = viewer.getInventory().addItem(taken);
        if (!rem.isEmpty()) {
            for (ItemStack remItem : rem.values()) {
                // If can't fit completely, put back or drop
                plugin.getTradePointManager().putStorageItem(point, si.slot(), remItem);
                viewer.sendMessage(MessageUtils.parse(viewer, "<yellow>Инвентарь полон, часть предмета осталась на складе.</yellow>"));
            }
        }
        render();
    }

    private void listFromStorage(StorageItem si) {
        int emptyShelf = findFreeSellShelf();
        if (emptyShelf < 0) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<red>На витрине нет свободных полок!</red>"));
            return;
        }
        ItemStack taken = plugin.getTradePointManager().takeStorageItem(point, si.slot());
        if (taken == null) return;

        new PriceGui(plugin, viewer, taken, false,
                price -> {
                    ListingResult res = plugin.getTradePointManager().addSellListing(viewer, point, emptyShelf, taken, price);
                    if (res == ListingResult.OK) {
                        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Предмет со склада успешно выставлен на витрину!</green>"));
                    } else {
                        // Put back into storage
                        plugin.getTradePointManager().putStorageItem(point, si.slot(), taken);
                        viewer.sendMessage(MessageUtils.parse(viewer, "<red>Не удалось выставить предмет.</red>"));
                    }
                    new StallStorageGui(plugin, viewer, point).open();
                },
                () -> {
                    // Put back into storage
                    plugin.getTradePointManager().putStorageItem(point, si.slot(), taken);
                    new StallStorageGui(plugin, viewer, point).open();
                }
        ).open();
    }

    private int findFreeSellShelf() {
        var listings = plugin.getTradePointManager().listings(point)
                .stream().filter(l -> l.type() == dev.lovelace.loveshops.market.model.ListingType.SELL).toList();
        Map<Integer, Boolean> taken = new HashMap<>();
        for (var l : listings) taken.put(l.slot(), true);
        for (int i = 0; i < point.sellSlots(); i++) {
            if (!taken.containsKey(i)) return i;
        }
        return -1;
    }

    private void depositToStorage(ItemStack item) {
        int cap = plugin.getTradePointManager().getStorageCapacity(point);
        List<StorageItem> stored = plugin.getTradePointManager().getStorage(point);
        if (stored.size() >= cap) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<red>Склад заполнен (макс. " + cap + " стеков)!</red>"));
            return;
        }

        // Find next free slot in storage
        Map<Integer, Boolean> occupied = new HashMap<>();
        for (var si : stored) occupied.put(si.slot(), true);
        int nextSlot = 0;
        while (occupied.containsKey(nextSlot)) nextSlot++;

        ItemStack deposited = item.clone();
        item.setAmount(0); // remove from player inventory

        plugin.getTradePointManager().putStorageItem(point, nextSlot, deposited);
        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Предмет помещён на склад.</green>"));
        render();
    }

    private void collectAll() {
        List<StorageItem> stored = plugin.getTradePointManager().getStorage(point);
        if (stored.isEmpty()) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<gray>Склад пуст.</gray>"));
            return;
        }
        int collected = 0;
        for (StorageItem si : new ArrayList<>(stored)) {
            ItemStack taken = plugin.getTradePointManager().takeStorageItem(point, si.slot());
            if (taken == null) continue;
            var rem = viewer.getInventory().addItem(taken);
            if (!rem.isEmpty()) {
                for (ItemStack remItem : rem.values()) {
                    plugin.getTradePointManager().putStorageItem(point, si.slot(), remItem);
                }
                viewer.sendMessage(MessageUtils.parse(viewer, "<yellow>Инвентарь заполнился! Часть предметов осталась на складе.</yellow>"));
                break;
            }
            collected++;
        }
        if (collected > 0) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<green>Собрано предметов со склада: " + collected + " ст.</green>"));
        }
        render();
    }
}
