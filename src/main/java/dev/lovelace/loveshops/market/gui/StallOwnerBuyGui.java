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
 * 27-slot Buy Orders GUI for trade point owner.
 * Supports Drag-to-Price buy order creation, price editing, collecting bought items, and cancellation.
 */
public final class StallOwnerBuyGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;
    private final Map<Integer, StallListing> listingAt = new HashMap<>();
    private final Map<Integer, Integer> emptySlotAt = new HashMap<>();

    public StallOwnerBuyGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<gold>Торговая точка — скупка</gold>");
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
        emptySlotAt.clear();

        // Header
        inventory.setItem(0, headerHead());
        inventory.setItem(4, collectAllItem());

        // Work zone: up to 7 content slots (10 to 16)
        int[] content = MarketLayout.contentSlots(SIZE);
        int maxSlots = Math.min(point.buySlots(), content.length);

        List<StallListing> listings = plugin.getTradePointManager().listings(point)
                .stream().filter(l -> l.type() == ListingType.BUY).toList();

        Map<Integer, StallListing> bySlot = new HashMap<>();
        for (StallListing l : listings) bySlot.put(l.slot(), l);

        for (int i = 0; i < maxSlots; i++) {
            int slot = content[i];
            StallListing listing = bySlot.get(i);
            if (listing != null) {
                inventory.setItem(slot, orderItem(listing));
                listingAt.put(slot, listing);
            } else {
                inventory.setItem(slot, emptyOrderItem(i));
                emptySlotAt.put(slot, i);
            }
        }

        // Footer
        inventory.setItem(MarketLayout.extraSlot(SIZE), head(HeadTextures.BANKER_ACCOUNT, "<gold>Касса точки</gold>", List.of(
                "",
                "<gray>Баланс кассы:</gray> " + plugin.getMarketStyle().money(point.tillCoins()),
                "<gray>Деньги на выкуп списываются из кассы.</gray>",
                "",
                "<green>ЛКМ </green><gray>— забрать кассу</gray>"
        )));
        inventory.setItem(MarketLayout.backSlot(SIZE), head(HeadTextures.BUTTON_BACK, "<yellow>Назад</yellow>",
                List.of("", "<gray>В главное меню точки</gray>", "<yellow>ЛКМ </yellow><gray>— вернуться</gray>")));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        refreshClient();
    }

    private ItemStack headerHead() {
        int active = plugin.getMarketRepository().countListings(point.claimId(), ListingType.BUY);
        return head(HeadTextures.BANKER_INFO, "<aqua>Скупка товаров</aqua>", List.of(
                "",
                "<gray>Активно ордеров: <white>" + active + "</white> / <white>" + point.buySlots() + "</white></gray>",
                "<gray>Уровень точки: <white>" + point.level() + "</white></gray>",
                "",
                "<gray>Игроки могут сдавать вам указанные предметы,</gray>",
                "<gray>получая оплату из вашей кассы.</gray>"
        ));
    }

    private ItemStack collectAllItem() {
        return icon(Material.HOPPER, "<green>Собрать все скупленные предметы</green>", List.of(
                "",
                "<gray>Забирает предметы со всех выполненных</gray>",
                "<gray>ордеров скупки в ваш инвентарь.</gray>",
                "",
                "<green>ЛКМ </green><gray>— собрать всё</gray>"
        ));
    }

    private ItemStack orderItem(StallListing listing) {
        ItemStack item = listing.template().clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<gray>Ордер: <white>#" + (listing.slot() + 1) + "</white></gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>Скуплено: <green>" + listing.stock() + "</green> / <white>" + listing.maxAmount() + "</white> шт.</gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>Цена выкупа: " + plugin.getMarketStyle().money(listing.price()) + " <gray>за шт.</gray></gray>"));
        lore.add(Component.empty());
        if (listing.stock() > 0) {
            lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— забрать скупленные предметы (" + listing.stock() + " шт.)</gray>"));
        } else {
            lore.add(MessageUtils.parse(viewer, "<gray>Пока ничего не скуплено</gray>"));
        }
        lore.add(MessageUtils.parse(viewer, "<yellow>ПКМ </yellow><gray>— изменить цену скупки</gray>"));
        lore.add(MessageUtils.parse(viewer, "<red>Shift+ПКМ </red><gray>— отменить ордер</gray>"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyOrderItem(int index) {
        return icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>Ордер #" + (index + 1) + " (свободен)</aqua>", List.of(
                "",
                "<gray>Слот готов для нового заказа на скупку.</gray>",
                "",
                "<yellow>Перетащите предмет </yellow><gray>сюда курсором,</gray>",
                "<yellow>или кликните </yellow><gray>с образцом в руке,</gray>",
                "<gray>чтобы настроить цену скупки.</gray>"
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
            TradePointManager.TillResult res = plugin.getTradePointManager().collectTill(viewer, point);
            if (res.ok()) {
                plugin.getMarketMessages().send(viewer, "till-collected", "money", plugin.getMarketStyle().money(res.amount()));
            } else if ("till-empty".equals(res.reason())) {
                plugin.getMarketMessages().send(viewer, "till-empty");
            }
            render();
            return;
        }

        if (rawSlot == 4) {
            // Collect all bought items
            collectAll();
            return;
        }

        // Slot clicked in top inventory
        if (rawSlot >= 0 && rawSlot < SIZE) {
            ItemStack cursor = event.getCursor();

            // Clicking empty order slot
            if (emptySlotAt.containsKey(rawSlot)) {
                int orderIndex = emptySlotAt.get(rawSlot);
                ItemStack sample = (cursor != null && !cursor.getType().isAir()) ? cursor : viewer.getInventory().getItemInMainHand();

                if (sample == null || sample.getType().isAir()) {
                    viewer.sendMessage(MessageUtils.parse(viewer, "<yellow>Возьмите образец предмета в руку или перетащите его курсором!</yellow>"));
                    return;
                }

                ListingResult check = plugin.getTradePointManager().previewItem(viewer, point, sample);
                if (check != ListingResult.OK) {
                    reportError(check);
                    return;
                }

                ItemStack template = sample.clone();
                template.setAmount(1);

                // Open PriceGui for buy order pricing
                new PriceGui(plugin, viewer, template, false,
                        price -> {
                            promptNumber("prompt-buy-amount", 1, 1000,
                                    () -> new StallOwnerBuyGui(plugin, viewer, point).open(),
                                    amount -> {
                                        ListingResult res = plugin.getTradePointManager().addBuyListing(viewer, point, orderIndex, template, price, (int) amount);
                                        if (res == ListingResult.OK) {
                                            viewer.sendMessage(MessageUtils.parse(viewer, "<green>Ордер на скупку успешно создан!</green>"));
                                        } else {
                                            reportError(res);
                                        }
                                        new StallOwnerBuyGui(plugin, viewer, point).open();
                                    });
                        },
                        () -> new StallOwnerBuyGui(plugin, viewer, point).open()
                ).open();
                return;
            }

            // Clicking existing order
            StallListing listing = listingAt.get(rawSlot);
            if (listing != null) {
                if (click == ClickType.SHIFT_RIGHT) {
                    // Cancel order and collect whatever was bought
                    TradePointManager.CollectResult res = plugin.getTradePointManager().collectListing(viewer, point, listing.id(), true);
                    if (res.ok()) {
                        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Ордер скупки отменён.</green>"));
                    }
                    render();
                } else if (click == ClickType.RIGHT) {
                    // Edit price
                    editPrice(listing);
                } else if (click == ClickType.LEFT) {
                    // Collect items
                    collectItems(listing);
                }
            }
        }
    }

    private void collectAll() {
        List<StallListing> orders = plugin.getTradePointManager().listings(point)
                .stream().filter(l -> l.type() == ListingType.BUY && l.stock() > 0).toList();
        if (orders.isEmpty()) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<gray>Скупленных предметов пока нет.</gray>"));
            return;
        }
        for (StallListing l : orders) {
            plugin.getTradePointManager().collectListing(viewer, point, l.id(), false);
        }
        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Все скупленные предметы перенесены в инвентарь.</green>"));
        render();
    }

    private void collectItems(StallListing listing) {
        if (listing.stock() <= 0) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<gray>По этому ордеру пока ничего не скуплено.</gray>"));
            return;
        }
        TradePointManager.CollectResult res = plugin.getTradePointManager().collectListing(viewer, point, listing.id(), false);
        if (res.ok()) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<green>Скупленные предметы (" + res.items() + " шт.) получены.</green>"));
        } else {
            viewer.sendMessage(MessageUtils.parse(viewer, "<red>Не удалось забрать предметы.</red>"));
        }
        render();
    }

    private void editPrice(StallListing listing) {
        new PriceGui(plugin, viewer, listing.template(), true,
                newPrice -> {
                    ListingResult res = plugin.getTradePointManager().changePrice(viewer, point, listing.id(), newPrice);
                    if (res == ListingResult.OK) {
                        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Цена скупки успешно изменена!</green>"));
                    } else {
                        reportError(res);
                    }
                    new StallOwnerBuyGui(plugin, viewer, point).open();
                },
                () -> new StallOwnerBuyGui(plugin, viewer, point).open()
        ).open();
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
            default -> msg.send(viewer, "listing-error");
        }
    }
}
