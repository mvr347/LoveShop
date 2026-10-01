package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.GuardService;
import dev.lovelace.loveshops.market.MarketRepository.RatingSummary;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.StallUpgradeService;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 27-slot Owner Hub GUI for trade point management.
 * Modular navigation to sub-menus: Sell Shelves, Buy Orders, Storage, Guard, Blacklist, Discounts.
 */
public final class StallOwnerGui extends MarketGui {

    private static final int SIZE = 27;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final TradePoint point;

    public StallOwnerGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(viewer.getName()));
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        MarketLayout.frame(inventory);

        // Header: slot 0 owner head, slots 2, 3, 5, 6 navigation tiles
        inventory.setItem(0, ownerHead());
        inventory.setItem(2, sellItem());
        inventory.setItem(3, buyItem());
        inventory.setItem(5, storageItem());
        inventory.setItem(6, tillItem());

        // Work zone (Row 1): slots 10, 12, 14, 16 management buttons
        inventory.setItem(10, modeItem());
        inventory.setItem(12, guardItem());
        inventory.setItem(14, discountItem());
        inventory.setItem(16, blacklistItem());

        // Footer: slot 20 transfer, 22 upgrade, 24 toggle open/close, 26 close
        inventory.setItem(20, transferItem());
        inventory.setItem(22, upgradeItem());
        inventory.setItem(MarketLayout.extraSlot(SIZE), toggleItem());
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>", List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        refreshClient();
    }

    private ItemStack ownerHead() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(MessageUtils.parse(viewer, "<gold>Торговая точка</gold> <white>" + viewer.getName() + "</white>"));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<gray>Уровень: <white>" + point.level() + "</white></gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>Касса: " + plugin.getMarketStyle().money(point.tillCoins()) + "</gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>Статус: " + (point.open() ? "<green>Открыто</green>" : "<red>Закрыто</red>") + "</gray>"));

        RatingSummary s = plugin.getRatingService().summary(point);
        String star = plugin.getMarketStyle().icon(MarketStyle.Icon.STAR);
        if (s.count() > 0) {
            lore.add(MessageUtils.parse(viewer, "<gold>" + star + " " + String.format(Locale.ROOT, "%.1f", s.average()) + "</gold> <gray>(" + s.count() + ")</gray>"));
        } else {
            lore.add(MessageUtils.parse(viewer, "<gray>Рейтинг: оценок нет</gray>"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack sellItem() {
        return head(HeadTextures.BANKER_DEPOSIT, "<gold>Витрина товаров</gold>", List.of(
                "",
                "<gray>Товары, выставленные на продажу.</gray>",
                "<gray>Полок занято: <white>" + plugin.getMarketRepository().countListings(point.claimId(), dev.lovelace.loveshops.market.model.ListingType.SELL)
                        + "</white> / <white>" + point.sellSlots() + "</white></gray>",
                "",
                "<green>ЛКМ </green><gray>— открыть витрину</gray>"
        ));
    }

    private ItemStack buyItem() {
        return head(HeadTextures.BANKER_WITHDRAW, "<aqua>Скупка товаров</aqua>", List.of(
                "",
                "<gray>Заказы на скупку предметов у игроков.</gray>",
                "<gray>Ордеров активно: <white>" + plugin.getMarketRepository().countListings(point.claimId(), dev.lovelace.loveshops.market.model.ListingType.BUY)
                        + "</white> / <white>" + point.buySlots() + "</white></gray>",
                "",
                "<green>ЛКМ </green><gray>— открыть скупку</gray>"
        ));
    }

    private ItemStack storageItem() {
        int cap = plugin.getTradePointManager().getStorageCapacity(point);
        int stored = plugin.getTradePointManager().getStorage(point).size();
        return icon(Material.BARREL, "<gold>Склад точки</gold>", List.of(
                "",
                "<gray>Хранение предметов точки.</gray>",
                "<gray>Занято стеков: <white>" + stored + "</white> / <white>" + cap + "</white></gray>",
                "",
                "<green>ЛКМ </green><gray>— открыть склад</gray>"
        ));
    }

    private ItemStack tillItem() {
        return head(HeadTextures.BANKER_ACCOUNT, "<gold>Касса точки</gold>", List.of(
                "",
                "<gray>Текущий баланс:</gray> " + plugin.getMarketStyle().money(point.tillCoins()),
                "<gray>Сюда поступает доход от продаж</gray>",
                "<gray>и отсюда оплачивается аренда и скупка.</gray>",
                "",
                "<green>ЛКМ </green><gray>— забрать монеты в инвентарь</gray>",
                "<yellow>Shift+ЛКМ </yellow><gray>— внести монеты из руки</gray>"
        ));
    }

    private ItemStack modeItem() {
        TradingMode mode = point.tradingMode();
        String modeName = switch (mode) {
            case BOTH -> "<green>Витрина и скупка (Оба)</green>";
            case SELL_ONLY -> "<yellow>Только витрина</yellow>";
            case BUY_ONLY -> "<aqua>Только скупка</aqua>";
        };
        return icon(Material.COMPARATOR, "<gold>Режим торговли</gold>", List.of(
                "",
                "<gray>Текущий режим: " + modeName + "</gray>",
                "",
                "<gray>В режиме <yellow>Только витрина</yellow> покупатели</gray>",
                "<gray>видят только ваши лоты на продажу.</gray>",
                "<gray>В режиме <aqua>Только скупка</aqua> — только заказы.</gray>",
                "",
                "<yellow>ЛКМ </yellow><gray>— переключить режим</gray>"
        ));
    }

    private ItemStack guardItem() {
        GuardState state = point.guardState();
        String status = switch (state) {
            case ACTIVE -> "<green>Активна</green> <gray>до " + WHEN.format(Instant.ofEpochMilli(point.guardPaidUntil())) + "</gray>";
            case UNPAID -> "<red>Не оплачена</red> <gray>(срок истёк)</gray>";
            case NONE -> "<gray>Не нанята</gray>";
        };
        return icon(Material.IRON_HELMET, "<blue>Стража</blue>", List.of(
                "",
                "<gray>Статус: " + status + "</gray>",
                "<gray>Стража защищает точку от ограблений</gray>",
                "<gray>и прогоняет назойливых игроков.</gray>",
                "",
                "<green>ЛКМ </green><gray>— меню управления стражей</gray>"
        ));
    }

    private ItemStack discountItem() {
        int count = 0;
        try {
            count = plugin.getMarketRepository().loadDiscounts(point.claimId()).size();
        } catch (Exception ignored) {}
        return icon(Material.NAME_TAG, "<gold>Персональные скидки</gold>", List.of(
                "",
                "<gray>Скидки для постоянных клиентов.</gray>",
                "<gray>Активных скидок: <white>" + count + "</white></gray>",
                "",
                "<green>ЛКМ </green><gray>— открыть управление скидками</gray>"
        ));
    }

    private ItemStack blacklistItem() {
        int count = 0;
        try {
            count = plugin.getMarketRepository().loadBlacklist(point.claimId()).size();
        } catch (Exception ignored) {}
        return icon(Material.WITHER_SKELETON_SKULL, "<red>Чёрный список</red>", List.of(
                "",
                "<gray>Игроки из этого списка не могут</gray>",
                "<gray>торговать с вашей точкой.</gray>",
                "<gray>В списке: <white>" + count + "</white> / <white>" + plugin.getMarketConfig().blacklistMaxEntries() + "</white></gray>",
                "",
                "<red>ЛКМ </red><gray>— открыть чёрный список</gray>"
        ));
    }

    private ItemStack transferItem() {
        return icon(Material.WRITABLE_BOOK, "<gold>Передать точку</gold>", List.of(
                "",
                "<gray>Передать или продать права аренды</gray>",
                "<gray>другому игроку.</gray>",
                "",
                "<yellow>ЛКМ </yellow><gray>— начать передачу</gray>"
        ));
    }

    private ItemStack upgradeItem() {
        StallUpgradeService svc = plugin.getUpgradeService();
        boolean max = svc.isMax(point);
        long cost = svc.nextCost(point);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("<gray>Текущий уровень: <white>" + point.level() + "</white> / <white>" + plugin.getMarketConfig().maxLevel() + "</white></gray>");
        lore.add("<gray>Полок витрины: <white>" + point.sellSlots() + "</white></gray>");
        lore.add("<gray>Ордеров скупки: <white>" + point.buySlots() + "</white></gray>");
        lore.add("<gray>Вместимость склада: <white>" + plugin.getTradePointManager().getStorageCapacity(point) + "</white> ст.</gray>");
        lore.add("");
        if (max) {
            lore.add("<gray>Максимальный уровень достигнут.</gray>");
        } else {
            lore.add("<gray>Стоимость улучшения: " + plugin.getMarketStyle().money(cost) + "</gray>");
            lore.add("");
            lore.add("<green>ЛКМ </green><gray>— улучшить точку</gray>");
        }
        return icon(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "<gold>Улучшение точки</gold>", lore);
    }

    private ItemStack toggleItem() {
        boolean open = point.open();
        String name = open ? "<red>Закрыть точку</red>" : "<green>Открыть точку</green>";
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (open) {
            lore.add("<gray>Покупатели не смогут взаимодействовать</gray>");
            lore.add("<gray>с закрытой точкой.</gray>");
            lore.add("");
            lore.add("<red>ЛКМ </red><gray>— закрыть</gray>");
        } else {
            lore.add("<gray>Открывает точку для всех игроков.</gray>");
            if (point.closeReason() != null) {
                lore.add("<gray>Причина закрытия: <white>" + point.closeReason().name() + "</white></gray>");
            }
            lore.add("");
            lore.add("<green>ЛКМ </green><gray>— открыть</gray>");
        }
        return icon(open ? Material.RED_DYE : Material.LIME_DYE, name, lore);
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
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        ClickType click = event.getClick();

        if (slot == MarketLayout.closeSlot(SIZE)) {
            viewer.closeInventory();
            return;
        }

        switch (slot) {
            case 2 -> new StallOwnerSellGui(plugin, viewer, point).open();
            case 3 -> new StallOwnerBuyGui(plugin, viewer, point).open();
            case 5 -> new StallStorageGui(plugin, viewer, point).open();
            case 6 -> {
                if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                    depositTill();
                } else {
                    withdrawTill();
                }
            }
            case 10 -> toggleMode();
            case 12 -> new StallGuardGui(plugin, viewer, point).open();
            case 14 -> new StallDiscountGui(plugin, viewer, point).open();
            case 16 -> new StallBlacklistGui(plugin, viewer, point).open();
            case 20 -> promptTransfer();
            case 22 -> upgrade();
            case 24 -> toggleOpen();
        }
    }

    private void withdrawTill() {
        TradePointManager.TillResult res = plugin.getTradePointManager().collectTill(viewer, point);
        var msg = plugin.getMarketMessages();
        if (res.ok()) {
            msg.send(viewer, "till-collected", "money", plugin.getMarketStyle().money(res.amount()));
        } else if ("till-empty".equals(res.reason())) {
            msg.send(viewer, "till-empty");
        } else if ("till-no-space".equals(res.reason())) {
            msg.send(viewer, "till-no-space");
        } else {
            msg.send(viewer, "economy-down");
        }
        render();
    }

    private void depositTill() {
        var eco = plugin.getEconomy().orElse(null);
        if (eco == null) {
            plugin.getMarketMessages().send(viewer, "economy-down");
            return;
        }
        ItemStack hand = viewer.getInventory().getItemInMainHand();
        if (hand == null || !eco.isCoin(hand)) {
            viewer.sendMessage(MessageUtils.parse(viewer, "<red>Возьмите монеты в руку для внесения в кассу!</red>"));
            return;
        }
        long value = eco.valueOf(hand);
        if (value <= 0) return;
        if (eco.charge(viewer, value)) {
            point.tillCoins(point.tillCoins() + value);
            plugin.getTradePointManager().save(point);
            viewer.sendMessage(MessageUtils.parse(viewer, "<green>В кассу внесено: " + CoinFormat.formatGlyphs(eco, value) + "</green>"));
            render();
        }
    }

    private void toggleMode() {
        TradingMode next = switch (point.tradingMode()) {
            case BOTH -> TradingMode.SELL_ONLY;
            case SELL_ONLY -> TradingMode.BUY_ONLY;
            case BUY_ONLY -> TradingMode.BOTH;
        };
        point.tradingMode(next);
        plugin.getTradePointManager().save(point);
        plugin.getTradePointManager().updateNpc(point);
        plugin.getTradePointManager().refreshViewers(point.claimId());
    }

    private void toggleOpen() {
        var mgr = plugin.getTradePointManager();
        var res = mgr.toggleOpen(viewer, point);
        switch (res) {
            case OPENED -> plugin.getMarketMessages().send(viewer, "shop-opened");
            case CLOSED -> plugin.getMarketMessages().send(viewer, "shop-closed");
            case DENIED_REASON -> {
                if (point.closeReason() == CloseReason.ADMIN) {
                    plugin.getMarketMessages().send(viewer, "shop-closed-admin");
                } else if (point.closeReason() == CloseReason.REPUTATION) {
                    plugin.getMarketMessages().send(viewer, "shop-closed-reputation");
                }
            }
            case NOT_OWNER -> plugin.getMarketMessages().send(viewer, "not-owner");
        }
        render();
    }

    private void upgrade() {
        StallUpgradeService.Result res = plugin.getUpgradeService().upgrade(viewer, point);
        var msg = plugin.getMarketMessages();
        switch (res) {
            case OK -> msg.send(viewer, "upgrade-done", "level", String.valueOf(point.level()));
            case MAX_LEVEL -> msg.send(viewer, "upgrade-max");
            case NO_MONEY -> msg.send(viewer, "upgrade-no-money");
            case NOT_OWNER -> msg.send(viewer, "not-owner");
            case ECONOMY_DOWN -> msg.send(viewer, "economy-down");
            case DB_ERROR -> msg.send(viewer, "listing-error");
        }
        render();
    }

    private void promptTransfer() {
        viewer.closeInventory();
        promptPlayer("prompt-transfer-target", this::open, target -> {
            if (target.getUniqueId().equals(viewer.getUniqueId())) {
                viewer.sendMessage(MessageUtils.parse(viewer, "<red>Нельзя передать точку самому себе!</red>"));
                return;
            }
            if (!target.isOnline()) {
                viewer.sendMessage(MessageUtils.parse(viewer, "<red>Игрок должен быть в сети!</red>"));
                return;
            }
            boolean success = plugin.getTradePointManager().transfer(point, target.getUniqueId());
            if (success) {
                viewer.sendMessage(MessageUtils.parse(viewer, "<green>Торговая точка успешно передана игроку " + target.getName() + "!</green>"));
                target.sendMessage(MessageUtils.parse(target, "<green>Вам передана торговая точка от " + viewer.getName() + "!</green>"));
            } else {
                viewer.sendMessage(MessageUtils.parse(viewer, "<red>Не удалось передать точку.</red>"));
            }
        });
    }
}
