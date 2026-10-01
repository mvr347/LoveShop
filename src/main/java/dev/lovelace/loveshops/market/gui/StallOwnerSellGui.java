package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.TradePointManager.ListingResult;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
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
 * 27-slot Sell Shelves GUI for trade point owner.
 * Supports Drag-to-Price listing creation, price editing, and taking down goods.
 */
public final class StallOwnerSellGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;
    private final Map<Integer, StallListing> listingAt = new HashMap<>();
    private final Map<Integer, Integer> emptyShelfAt = new HashMap<>();

    public StallOwnerSellGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<gold>Торговая точка — витрина</gold>");
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        MarketLayout.frame(inventory);
        listingAt.clear();
        emptyShelfAt.clear();

        // Header
        inventory.setItem(0, headerHead());

        // Work zone: up to 7 content slots (10 to 16)
        int[] content = MarketLayout.contentSlots(SIZE);
        int maxShelves = Math.min(point.sellSlots(), content.length);

        List<StallListing> listings = plugin.getTradePointManager().listings(point)
                .stream().filter(l -> l.type() == ListingType.SELL).toList();

        Map<Integer, StallListing> byShelf = new HashMap<>();
        for (StallListing l : listings) byShelf.put(l.slot(), l);

        for (int shelf = 0; shelf < maxShelves; shelf++) {
            int slot = content[shelf];
            StallListing listing = byShelf.get(shelf);
            if (listing != null && listing.stock() > 0) {
                inventory.setItem(slot, shelfItem(listing));
                listingAt.put(slot, listing);
            } else {
                inventory.setItem(slot, emptyShelfItem(shelf));
                emptyShelfAt.put(slot, shelf);
            }
        }

        // Footer
        inventory.setItem(MarketLayout.extraSlot(SIZE), icon(Material.BARREL, "<gold>Перейти на склад</gold>", List.of(
                "",
                "<gray>Открыть склад предметов точки.</gray>",
                "",
                "<green>ЛКМ </green><gray>— открыть склад</gray>"
        )));
        inventory.setItem(MarketLayout.backSlot(SIZE), head(HeadTextures.BUTTON_BACK, "<yellow>Назад</yellow>",
                List.of("", "<gray>В главное меню точки</gray>", "<yellow>ЛКМ </yellow><gray>— вернуться</gray>")));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        refreshClient();
    }

    private ItemStack headerHead() {
        int active = plugin.getMarketRepository().countListings(point.claimId(), ListingType.SELL);
        return head(HeadTextures.BANKER_INFO, "<gold>Витрина товаров</gold>", List.of(
                "",
                "<gray>Полок занято: <white>" + active + "</white> / <white>" + point.sellSlots() + "</white></gray>",
                "<gray>Уровень точки: <white>" + point.level() + "</white></gray>",
                "",
                "<gray>Чтобы выставить товар, перетащите</gray>",
                "<gray>предмет на свободную полку.</gray>"
        ));
    }

    private ItemStack shelfItem(StallListing listing) {
        ItemStack item = listing.template().clone();
        item.setAmount(Math.max(1, Math.min(listing.stock(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<gray>Полка: <white>#" + (listing.slot() + 1) + "</white></gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>В наличии: <white>" + listing.stock() + "</white> шт.</gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>Цена: " + plugin.getMarketStyle().money(listing.price()) + " <gray>за шт.</gray></gray>"));
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— изменить цену лота</gray>"));
        lore.add(MessageUtils.parse(viewer, "<red>ПКМ </red><gray>— снять товар с продажи</gray>"));
        lore.add(MessageUtils.parse(viewer, "<yellow>Shift+ЛКМ </yellow><gray>— пополнить запас из руки</gray>"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyShelfItem(int shelf) {
        return icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<green>Полка #" + (shelf + 1) + " (свободна)</green>", List.of(
                "",
                "<gray>Полка готова для размещения товара.</gray>",
                "",
                "<yellow>Перетащите предмет </yellow><gray>сюда курсором,</gray>",
                "<yellow>или кликните </yellow><gray>с предметом в руке</gray>",
                "<gray>для открытия настройки цены.</gray>"
        ));
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

        if (rawSlot == MarketLayout.extraSlot(SIZE)) {
            new StallStorageGui(plugin, viewer, point).open();
            return;
        }

        // Shelf clicked in top inventory
        if (rawSlot >= 0 && rawSlot < SIZE) {
            ItemStack cursor = event.getCursor();

            // Clicking empty shelf
            if (emptyShelfAt.containsKey(rawSlot)) {
                int shelfIndex = emptyShelfAt.get(rawSlot);
                ItemStack toList = (cursor != null && !cursor.getType().isAir()) ? cursor : viewer.getInventory().getItemInMainHand();

                if (toList == null || toList.getType().isAir()) {
                    viewer.sendMessage(MessageUtils.parse(viewer, "<yellow>Возьмите предмет в руку или перетащите его курсором!</yellow>"));
                    return;
                }

                ListingResult check = plugin.getTradePointManager().previewItem(viewer, point, toList);
                if (check != ListingResult.OK) {
                    reportError(check);
                    return;
                }

                // If from cursor, clear cursor so player doesn't dupe while pricing
                boolean fromCursor = (cursor != null && !cursor.getType().isAir());
                ItemStack itemToPrice = toList.clone();
                if (fromCursor) {
                    event.getView().setCursor(null);
                } else {
                    viewer.getInventory().setItemInMainHand(null);
                }

                // Open PriceGui for this item
                new PriceGui(plugin, viewer, itemToPrice, false,
                        price -> {
                            ListingResult res = plugin.getTradePointManager().addSellListing(viewer, point, shelfIndex, itemToPrice, price);
                            if (res == ListingResult.OK) {
                                viewer.sendMessage(MessageUtils.parse(viewer, "<green>Товар успешно выставлен на продажу!</green>"));
                            } else {
                                // Return item to inventory
                                giveBack(itemToPrice);
                                reportError(res);
                            }
                            new StallOwnerSellGui(plugin, viewer, point).open();
                        },
                        () -> {
                            giveBack(itemToPrice);
                            new StallOwnerSellGui(plugin, viewer, point).open();
                        }
                ).open();
                return;
            }

            // Clicking existing listing
            StallListing listing = listingAt.get(rawSlot);
            if (listing != null) {
                if (click == ClickType.RIGHT) {
                    // Take down goods
                    takeDown(listing);
                } else if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                    // Add stock from hand
                    addStockFromHand(listing);
                } else if (click == ClickType.LEFT) {
                    // Edit price
                    editPrice(listing);
                }
            }
        }
    }

    private void editPrice(StallListing listing) {
        new PriceGui(plugin, viewer, listing.template(), true,
                newPrice -> {
                    ListingResult res = plugin.getTradePointManager().changePrice(viewer, point, listing.id(), newPrice);
                    if (res == ListingResult.OK) {
                        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Цена успешно изменена!</green>"));
                    } else {
                        reportError(res);
                    }
                    new StallOwnerSellGui(plugin, viewer, point).open();
                },
                () -> new StallOwnerSellGui(plugin, viewer, point).open()
        ).open();
    }

    private void takeDown(StallListing listing) {
        TradePointManager.CollectResult res = plugin.getTradePointManager().collectListing(viewer, point, listing.id(), true);
        if (res.ok()) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<green>Товар снят с продажи и возвращён в инвентарь.</green>"));
        } else {
            viewer.sendMessage(MessageUtils.parse(viewer, "<red>Не удалось снять товар.</red>"));
        }
        render();
    }

    private void addStockFromHand(StallListing listing) {
        ListingResult res = plugin.getTradePointManager().addStock(viewer, point, listing.id());
        if (res == ListingResult.OK) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<green>Запас товара успешно пополнен!</green>"));
        } else {
            reportError(res);
        }
        render();
    }

    private void giveBack(ItemStack item) {
        var leftover = viewer.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack rem : leftover.values()) {
                viewer.getWorld().dropItem(viewer.getLocation(), rem);
            }
        }
    }

    private void reportError(ListingResult res) {
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
