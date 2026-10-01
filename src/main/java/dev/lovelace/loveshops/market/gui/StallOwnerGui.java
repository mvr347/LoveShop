package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.StallUpgradeService;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.TradePointManager.ListingResult;
import dev.lovelace.loveshops.market.GuardService;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * The stall owner's menu (54 slots, standalone). Tabs live in the header (slots 2-7, centred by
 * their number), the open/closed switch is the footer's extra button, the work zone shows the
 * content of the selected tab.
 */
public final class StallOwnerGui extends MarketGui {

    public enum Tab { CASH, SELL, BUY, UPGRADE, GUARD }

    private static final int SIZE = 54;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final TradePoint point;
    private Tab tab;
    /** Item of the price being asked or last rejected, so the error message can name that item's bounds. */
    private ItemStack priceItem;
    /** Content slot -> listing shown there (SELL/BUY tabs). */
    private final Map<Integer, StallListing> listingAt = new HashMap<>();
    /** Content slot -> shelf index of an empty, available shelf ("add" tile). */
    private final Map<Integer, Integer> emptyShelfAt = new HashMap<>();

    public StallOwnerGui(LoveShops plugin, Player viewer, TradePoint point) {
        this(plugin, viewer, point, Tab.CASH);
    }

    public StallOwnerGui(LoveShops plugin, Player viewer, TradePoint point, Tab tab) {
        super(plugin, viewer);
        this.point = point;
        this.tab = tab;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(viewer.getName()));
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    private Tab[] tabs() {
        return Tab.values();
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render() {
        MarketLayout.frame(inventory);
        listingAt.clear();
        emptyShelfAt.clear();

        inventory.setItem(0, ownerHead());

        Tab[] tabs = tabs();
        int[] slots = MarketLayout.controlSlots(tabs.length);
        for (int i = 0; i < tabs.length; i++) {
            inventory.setItem(slots[i], tabItem(tabs[i]));
        }

        inventory.setItem(MarketLayout.extraSlot(SIZE), toggleItem());
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>", List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        switch (tab) {
            case CASH -> renderCash();
            case SELL -> renderShelves(ListingType.SELL);
            case BUY -> renderShelves(ListingType.BUY);
            case UPGRADE -> renderUpgrade();
            case GUARD -> renderGuard();
        }
        refreshClient();
    }

    private ItemStack ownerHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(viewer);
            meta.displayName(MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(viewer.getName())));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(viewer, "<gray>Уровень торговца: <white>" + point.level() + "</white></gray>"));
            long end = plugin.getTradePointManager().rentEnd(point);
            if (end > 0) {
                lore.add(MessageUtils.parse(viewer, "<gray>Аренда до: <white>" + WHEN.format(Instant.ofEpochMilli(end)) + "</white></gray>"));
            }
            meta.lore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack tabItem(Tab t) {
        String base64;
        String name;
        String hint;
        switch (t) {
            case CASH -> {
                base64 = HeadTextures.BANKER_DEPOSIT_FILLED;
                name = "Касса";
                hint = "Выручка и состояние торговой точки";
            }
            case SELL -> {
                base64 = HeadTextures.TAB_SELLER;
                name = "Продажа";
                hint = "Товары на прилавке";
            }
            case BUY -> {
                base64 = HeadTextures.TAB_BUYER;
                name = "Скупка";
                hint = "Что точка покупает у игроков";
            }
            case UPGRADE -> {
                base64 = HeadTextures.BANKER_INFO;
                name = "Улучшения";
                hint = "Больше полок и заказов";
            }
            default -> {
                base64 = HeadTextures.WANDERER_INFO;
                name = "Стража";
                hint = "Защита от ограблений";
            }
        }
        boolean selected = t == tab;
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("<gray>" + hint + "</gray>");
        lore.add("");
        lore.add(selected ? "<green>▶ Открыто</green>" : "<yellow>ЛКМ </yellow><gray>— открыть</gray>");
        ItemStack item = head(base64, (selected ? "<gold>" : "<white>") + name + (selected ? "</gold>" : "</white>"), lore);
        if (selected && item.getItemMeta() != null) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack toggleItem() {
        MarketStyle style = plugin.getMarketStyle();
        if (point.open()) {
            return head(HeadTextures.MARKET_OPEN, "<green>Точка ОТКРЫТА</green>",
                    List.of("", "<gray>Покупатели могут торговать с вами.</gray>", "", "<red>ЛКМ </red><gray>— закрыть точку</gray>"));
        }
        CloseReason reason = point.closeReason();
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("<gray>Причина: <white>" + reasonText(reason) + "</white></gray>");
        lore.add("");
        if (reason == null || reason.ownerMayReopen()) {
            lore.add("<green>ЛКМ </green><gray>— открыть точку</gray>");
        } else {
            lore.add("<red>Открыть сейчас нельзя.</red>");
        }
        return head(HeadTextures.MARKET_CLOSED, style.icon(MarketStyle.Icon.CLOSED) + " <red>Точка ЗАКРЫТА</red>", lore);
    }

    private static String reasonText(CloseReason reason) {
        if (reason == null) return "не указана";
        return switch (reason) {
            case OWNER -> "закрыта вами";
            case ROBBERY -> "ограбление — откройте точку вручную";
            case RENT_GRACE -> "просрочена аренда";
            case REPUTATION -> "репутация не позволяет торговать";
            case ADMIN -> "закрыта администратором";
        };
    }

    private void renderCash() {
        MarketStyle style = plugin.getMarketStyle();
        int[] content = MarketLayout.contentSlots(SIZE);

        List<String> till = new ArrayList<>();
        till.add("");
        till.add("<gray>В кассе:</gray> " + style.money(point.tillCoins()));
        till.add("");
        till.add("<gray>Сюда идёт выручка от продаж. Из кассы</gray>");
        till.add("<gray>автоматически платится аренда и стража.</gray>");
        till.add("");
        till.add(point.tillCoins() > 0 ? "<green>ЛКМ </green><gray>— забрать деньги</gray>" : "<gray>Касса пуста</gray>");
        inventory.setItem(content[1], head(HeadTextures.BANKER_DEPOSIT_FILLED,
                style.icon(MarketStyle.Icon.TILL) + " <gold>Касса</gold>", till));

        List<String> stats = new ArrayList<>();
        stats.add("");
        stats.add("<gray>Продаж всего: <white>" + point.salesTotal() + "</white></gray>");
        stats.add("<gray>Выручка всего:</gray> " + style.money(point.revenueTotal()));
        stats.add("<gray>Полок продажи: <white>" + point.sellSlots() + "</white></gray>");
        stats.add("<gray>Заказов скупки: <white>" + point.buySlots() + "</white></gray>");
        inventory.setItem(content[3], head(HeadTextures.BANKER_INFO, "<gold>Статистика</gold>", stats));

        List<String> status = new ArrayList<>();
        status.add("");
        status.add(point.open() ? "<green>Точка открыта</green>" : "<red>Точка закрыта</red>");
        long end = plugin.getTradePointManager().rentEnd(point);
        if (end > 0) status.add("<gray>Аренда до: <white>" + WHEN.format(Instant.ofEpochMilli(end)) + "</white></gray>");
        status.add("");
        status.add("<gray>Переключатель — в правом нижнем углу.</gray>");
        inventory.setItem(content[5], head(point.open() ? HeadTextures.MARKET_OPEN : HeadTextures.MARKET_CLOSED,
                "<gold>Состояние</gold>", status));
    }

    private void renderShelves(ListingType type) {
        int[] content = MarketLayout.contentSlots(SIZE);
        int capacity = Math.min(content.length, type == ListingType.SELL ? point.sellSlots() : point.buySlots());
        Map<Integer, StallListing> byShelf = new HashMap<>();
        for (StallListing l : plugin.getTradePointManager().listings(point)) {
            if (l.type() == type) byShelf.put(l.slotIndex(), l);
        }
        for (int shelf = 0; shelf < capacity; shelf++) {
            int slot = content[shelf];
            StallListing l = byShelf.get(shelf);
            if (l != null) {
                listingAt.put(slot, l);
                inventory.setItem(slot, listingItem(l));
            } else {
                emptyShelfAt.put(slot, shelf);
                inventory.setItem(slot, addTile(type));
            }
        }
    }

    private ItemStack addTile(ListingType type) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        if (type == ListingType.SELL) {
            lore.add("<gray>Возьмите товар в руку и нажмите —</gray>");
            lore.add("<gray>вся стопка встанет на эту полку.</gray>");
        } else {
            lore.add("<gray>Возьмите образец предмета в руку и нажмите.</gray>");
            lore.add("<gray>Образец останется у вас.</gray>");
        }
        lore.add("");
        lore.add("<green>ЛКМ </green><gray>— добавить</gray>");
        return head(HeadTextures.BANKER_DEPOSIT_EMPTY, "<green>+ Свободная полка</green>", lore);
    }

    private ItemStack listingItem(StallListing l) {
        MarketStyle style = plugin.getMarketStyle();
        ItemStack item = l.template();
        item.setAmount(Math.max(1, Math.min(l.stock(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        if (l.type() == ListingType.SELL) {
            lore.add(MessageUtils.parse(viewer, "<gray>Цена за шт.:</gray> " + style.money(l.unitPrice())));
            lore.add(MessageUtils.parse(viewer, l.stock() > 0
                    ? "<gray>В наличии: <white>" + l.stock() + "</white></gray>"
                    : "<red>Распродано</red>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>с товаром в руке — добавить ещё</gray>"));
            lore.add(MessageUtils.parse(viewer, "<red>ПКМ </red><gray>— снять с продажи, забрать остаток</gray>"));
        } else {
            lore.add(MessageUtils.parse(viewer, "<gray>Платим за шт.:</gray> " + style.money(l.unitPrice())));
            lore.add(MessageUtils.parse(viewer, "<gray>Куплено: <white>" + l.stock() + "</white> / <white>" + l.maxAmount() + "</white></gray>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— забрать купленное</gray>"));
            lore.add(MessageUtils.parse(viewer, "<red>ПКМ </red><gray>— отменить заказ, забрать купленное</gray>"));
        }
        lore.add(MessageUtils.parse(viewer, "<yellow>Shift+ЛКМ </yellow><gray>— изменить цену</gray>"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        ClickType click = event.getClick();

        if (slot == MarketLayout.closeSlot(SIZE)) {
            viewer.closeInventory();
            return;
        }
        if (slot == MarketLayout.extraSlot(SIZE)) {
            onToggle();
            return;
        }
        Tab[] tabs = tabs();
        int[] tabSlots = MarketLayout.controlSlots(tabs.length);
        for (int i = 0; i < tabs.length; i++) {
            if (slot == tabSlots[i]) {
                if (tabs[i] != tab) {
                    tab = tabs[i];
                    render();
                }
                return;
            }
        }

        if (tab == Tab.CASH) {
            int[] content = MarketLayout.contentSlots(SIZE);
            if (slot == content[1]) onCollect();
            return;
        }
        if (tab == Tab.UPGRADE) {
            int[] content = MarketLayout.contentSlots(SIZE);
            if (slot == content[3]) onUpgrade();
            return;
        }
        if (tab == Tab.GUARD) {
            int[] content = MarketLayout.contentSlots(SIZE);
            if (slot == content[3]) onGuard();
            return;
        }

        StallListing listing = listingAt.get(slot);
        if (listing != null) {
            onListingClick(listing, click);
            return;
        }
        Integer shelf = emptyShelfAt.get(slot);
        if (shelf != null && (click == ClickType.LEFT || click == ClickType.SHIFT_LEFT)) {
            onAddShelf(tab == Tab.SELL ? ListingType.SELL : ListingType.BUY, shelf);
        }
    }

    private void onToggle() {
        TradePointManager m = plugin.getTradePointManager();
        switch (m.toggleOpen(viewer, point)) {
            case OPENED -> plugin.getMarketMessages().send(viewer, "shop-opened");
            case CLOSED -> plugin.getMarketMessages().send(viewer, "shop-closed");
            case DENIED_REASON -> plugin.getMarketMessages().send(viewer, "shop-open-denied", "reason", reasonText(point.closeReason()));
            case NOT_OWNER -> plugin.getMarketMessages().send(viewer, "not-owner");
        }
    }

    private void renderUpgrade() {
        MarketStyle style = plugin.getMarketStyle();
        StallUpgradeService up = plugin.getUpgradeService();
        int[] content = MarketLayout.contentSlots(SIZE);

        List<String> now = new ArrayList<>();
        now.add("");
        now.add("<gray>Уровень торговца: <white>" + point.level() + "</white> / <white>" + plugin.getMarketConfig().maxLevel() + "</white></gray>");
        now.add("<gray>Полок продажи: <white>" + point.sellSlots() + "</white></gray>");
        now.add("<gray>Заказов скупки: <white>" + point.buySlots() + "</white></gray>");
        inventory.setItem(content[1], head(HeadTextures.BANKER_INFO, "<gold>Сейчас</gold>", now));

        if (up.atMax(point)) {
            inventory.setItem(content[3], head(HeadTextures.MARKET_OPEN, "<green>Максимальный уровень</green>",
                    List.of("", "<gray>Дальше расти некуда.</gray>")));
            return;
        }
        long cost = up.nextCost(point);
        boolean fromTill = point.tillCoins() >= cost;
        List<String> next = new ArrayList<>();
        next.add("");
        next.add("<gray>Цена:</gray> " + style.money(cost));
        next.add(fromTill ? "<gray>Спишется из кассы.</gray>" : "<gray>Спишется из вашего кармана.</gray>");
        next.add("");
        next.add("<gray>Получите: <white>+1</white> полка продажи и <white>+1</white> заказ скупки.</gray>");
        next.add("");
        next.add("<green>ЛКМ </green><gray>— улучшить</gray>");
        inventory.setItem(content[3], head(HeadTextures.MARKET_OPEN, "<green>Улучшить до уровня " + (point.level() + 1) + "</green>", next));
    }

    private void renderGuard() {
        MarketStyle style = plugin.getMarketStyle();
        var cfg = plugin.getMarketConfig();
        int[] content = MarketLayout.contentSlots(SIZE);
        GuardState state = point.guardState();

        List<String> status = new ArrayList<>();
        status.add("");
        switch (state) {
            case ACTIVE -> {
                status.add("<green>Стража на посту</green>");
                status.add("<gray>Оплачено до: <white>" + WHEN.format(Instant.ofEpochMilli(point.guardPaidUntil())) + "</white></gray>");
            }
            case UNPAID -> {
                status.add("<red>Стража ушла: не хватило денег на зарплату</red>");
            }
            default -> status.add("<gray>Стражи нет</gray>");
        }
        status.add("");
        status.add("<gray>Зарплата:</gray> " + style.money(cfg.guardSalary()) + " <gray>за <white>" + cfg.guardSalaryPeriodHours() + "</white> ч</gray>");
        inventory.setItem(content[1], head(HeadTextures.WANDERER_INFO, style.icon(MarketStyle.Icon.GUARD) + " <gold>Стража</gold>", status));

        if (!cfg.guardEnabled()) {
            inventory.setItem(content[3], head(HeadTextures.MARKET_CLOSED, "<red>Стража отключена</red>", List.of()));
        } else if (state == GuardState.ACTIVE) {
            inventory.setItem(content[3], head(HeadTextures.MARKET_CLOSED, "<red>Уволить стражу</red>",
                    List.of("", "<gray>Зарплата за оплаченное время не возвращается.</gray>", "", "<red>ЛКМ </red><gray>— уволить</gray>")));
        } else {
            boolean fromTill = point.tillCoins() >= cfg.guardSalary();
            inventory.setItem(content[3], head(HeadTextures.MARKET_OPEN, "<green>Нанять стражу</green>",
                    List.of("", "<gray>Первый период оплачивается сразу:</gray>", style.money(cfg.guardSalary()),
                            fromTill ? "<gray>Спишется из кассы.</gray>" : "<gray>Спишется из вашего кармана.</gray>",
                            "", "<green>ЛКМ </green><gray>— нанять</gray>")));
        }

        inventory.setItem(content[5], head(HeadTextures.BANKER_INFO, "<gold>Что делает стража</gold>", List.of("",
                "<gray>Со стражей агрессивный игрок не может", "<gray>ограбить вашу точку: торговец не отдаёт", "<gray>товар и не закрывается.",
                "", "<gray>Кто слишком долго пристаёт к торговцу —", "<gray>того выведут с рынка.")));
    }

    private void onGuard() {
        GuardService guards = plugin.getGuardService();
        if (point.guardState() == GuardState.ACTIVE) {
            switch (guards.fire(viewer, point)) {
                case OK -> plugin.getMarketMessages().send(viewer, "guard-fired");
                default -> plugin.getMarketMessages().send(viewer, "listing-error");
            }
            return;
        }
        switch (guards.hire(viewer, point)) {
            case OK -> plugin.getMarketMessages().send(viewer, "guard-hired");
            case NO_MONEY -> plugin.getMarketMessages().send(viewer, "guard-no-money");
            case DISABLED -> plugin.getMarketMessages().send(viewer, "guard-disabled");
            case ALREADY -> plugin.getMarketMessages().send(viewer, "guard-hired");
            case NOT_OWNER -> plugin.getMarketMessages().send(viewer, "not-owner");
            case ECONOMY_DOWN -> plugin.getMarketMessages().send(viewer, "economy-down");
            case DB_ERROR -> plugin.getMarketMessages().send(viewer, "listing-error");
        }
    }

    private void onUpgrade() {
        switch (plugin.getUpgradeService().upgrade(viewer, point)) {
            case OK -> plugin.getMarketMessages().send(viewer, "upgrade-done", "level", String.valueOf(point.level()));
            case MAX_LEVEL -> plugin.getMarketMessages().send(viewer, "upgrade-max");
            case NO_MONEY -> plugin.getMarketMessages().send(viewer, "upgrade-no-money");
            case NOT_OWNER -> plugin.getMarketMessages().send(viewer, "not-owner");
            case ECONOMY_DOWN -> plugin.getMarketMessages().send(viewer, "economy-down");
            case DB_ERROR -> plugin.getMarketMessages().send(viewer, "listing-error");
        }
    }

    private void onCollect() {
        TradePointManager.TillResult result = plugin.getTradePointManager().collectTill(viewer, point);
        if (result.ok()) {
            plugin.getMarketMessages().send(viewer, "till-collected", "money", plugin.getMarketStyle().money(result.amount()));
        } else {
            plugin.getMarketMessages().send(viewer, result.reason());
        }
    }

    private void onListingClick(StallListing listing, ClickType click) {
        TradePointManager m = plugin.getTradePointManager();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            priceItem = listing.template();
            ask("prompt-price", m.minUnitPrice(listing.template()), m.maxUnitPrice(listing.template()),
                    price -> report(m.changePrice(viewer, point, listing.id(), price), "listing-price-changed"));
            return;
        }
        if (listing.type() == ListingType.SELL) {
            if (click == ClickType.LEFT) {
                report(m.addStock(viewer, point, listing.id()), "listing-stock-added");
            } else if (click == ClickType.RIGHT) {
                int moved = m.withdraw(viewer, point, listing.id(), true);
                plugin.getMarketMessages().send(viewer, moved < 0 ? "listing-error" : "listing-removed", "count", String.valueOf(moved));
            }
        } else {
            if (click == ClickType.LEFT) {
                int moved = m.withdraw(viewer, point, listing.id(), false);
                plugin.getMarketMessages().send(viewer, moved < 0 ? "listing-error" : "listing-collected", "count", String.valueOf(moved));
            } else if (click == ClickType.RIGHT) {
                int moved = m.withdraw(viewer, point, listing.id(), true);
                plugin.getMarketMessages().send(viewer, moved < 0 ? "listing-error" : "listing-removed", "count", String.valueOf(moved));
            }
        }
    }

    private void onAddShelf(ListingType type, int shelf) {
        TradePointManager m = plugin.getTradePointManager();
        ItemStack hand = viewer.getInventory().getItemInMainHand();
        ListingResult pre = m.previewItem(viewer, point, hand);
        if (pre != ListingResult.OK) {
            report(pre, null);
            return;
        }
        long min = m.minUnitPrice(hand);
        long max = m.maxUnitPrice(hand);
        priceItem = hand.clone();
        String itemName = hand.getType().name();
        if (type == ListingType.SELL) {
            ask("prompt-price", min, max,
                    price -> report(m.addSellListing(viewer, point, shelf, price), "listing-added"), "item", itemName);
        } else {
            ask("prompt-price", min, max, price ->
                    ask("prompt-max", 1, plugin.getMarketConfig().maxBuyAmount(), amount ->
                            report(m.addBuyListing(viewer, point, shelf, price, (int) amount), "listing-added"), "item", itemName),
                    "item", itemName);
        }
    }

    /** Tells the player how an owner action ended and returns to this menu. */
    private void report(ListingResult result, String okKey) {
        switch (result) {
            case OK -> { if (okKey != null) plugin.getMarketMessages().send(viewer, okKey); }
            case NOT_OWNER -> plugin.getMarketMessages().send(viewer, "not-owner");
            case NO_ITEM -> plugin.getMarketMessages().send(viewer, "listing-need-item");
            case IS_COIN -> plugin.getMarketMessages().send(viewer, "listing-is-coin");
            case FORBIDDEN -> plugin.getMarketMessages().send(viewer, "listing-forbidden");
            case PRICE_LOW -> plugin.getMarketMessages().send(viewer, "listing-price-low", "min",
                    plugin.getMarketStyle().money(priceItem == null ? 1L : plugin.getTradePointManager().minUnitPrice(priceItem)));
            case PRICE_HIGH -> plugin.getMarketMessages().send(viewer, "listing-price-high", "max",
                    plugin.getMarketStyle().money(priceItem == null ? plugin.getMarketConfig().priceMax()
                            : plugin.getTradePointManager().maxUnitPrice(priceItem)));
            case NO_SLOT -> plugin.getMarketMessages().send(viewer, "listing-no-slot");
            case SLOT_TAKEN -> plugin.getMarketMessages().send(viewer, "listing-slot-taken");
            case LISTING_GONE -> plugin.getMarketMessages().send(viewer, "listing-gone");
            case NO_SPACE -> plugin.getMarketMessages().send(viewer, "listing-no-space");
            case DB_ERROR -> plugin.getMarketMessages().send(viewer, "listing-error");
        }
    }

    /** Prompt that returns to the tab the owner was on. */
    private void ask(String messageKey, long min, long max, LongConsumer onValue, String... placeholders) {
        Tab returnTo = tab;
        promptNumber(messageKey, min, max, () -> reopen(returnTo), onValue, placeholders);
    }

    private void reopen(Tab returnTo) {
        if (!viewer.isOnline()) return;
        if (plugin.getChatPromptService().has(viewer)) return;
        if (!point.isOwner(viewer.getUniqueId())) return;
        new StallOwnerGui(plugin, viewer, point, returnTo).open();
    }
}
