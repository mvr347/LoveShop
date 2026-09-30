package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.ItemTransfer;
import dev.lovelace.loveshops.market.MarketRepository.RatingSummary;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.RatingService;
import dev.lovelace.loveshops.market.StallTradeService;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * What a customer sees at someone else's stall (54 slots, standalone): goods to buy, orders the
 * stall pays for, and the stall's rating. Big purchases ask for a confirmation first.
 */
public final class StallBuyerGui extends MarketGui {

    public enum Tab { GOODS, ORDERS, RATING }

    private static final int SIZE = 54;

    private final TradePoint point;
    private Tab tab;
    private final Map<Integer, StallListing> listingAt = new HashMap<>();

    public StallBuyerGui(LoveShops plugin, Player viewer, TradePoint point) {
        this(plugin, viewer, point, Tab.GOODS);
    }

    public StallBuyerGui(LoveShops plugin, Player viewer, TradePoint point, Tab tab) {
        super(plugin, viewer);
        this.point = point;
        this.tab = tab;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(point.ownerName()));
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render() {
        if (!point.isTrading()) {
            // Closed, robbed or released while this menu was open: leave nothing clickable behind.
            plugin.getMarketMessages().send(viewer, "stall-closed");
            viewer.closeInventory();
            return;
        }
        MarketLayout.frame(inventory);
        listingAt.clear();

        inventory.setItem(0, ownerHead());
        Tab[] tabs = Tab.values();
        int[] slots = MarketLayout.controlSlots(tabs.length);
        for (int i = 0; i < tabs.length; i++) inventory.setItem(slots[i], tabItem(tabs[i]));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        switch (tab) {
            case GOODS -> renderListings(ListingType.SELL);
            case ORDERS -> renderListings(ListingType.BUY);
            case RATING -> renderRating();
        }
        refreshClient();
    }

    private String ratingLine() {
        RatingSummary s = plugin.getRatingService().summary(point);
        String star = plugin.getMarketStyle().icon(MarketStyle.Icon.STAR);
        if (s.count() == 0) return "<gray>Оценок пока нет</gray>";
        return "<gold>" + star + " " + String.format(Locale.ROOT, "%.1f", s.average()) + "</gold> <gray>(" + s.count() + ")</gray>";
    }

    private ItemStack ownerHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(point.ownerUuid());
            meta.setOwningPlayer(owner);
            meta.displayName(MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(point.ownerName())));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(viewer, ratingLine()));
            lore.add(MessageUtils.parse(viewer, "<gray>Уровень торговца: <white>" + point.level() + "</white></gray>"));
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
            case GOODS -> { base64 = HeadTextures.TAB_SELLER; name = "Товары"; hint = "Что можно купить"; }
            case ORDERS -> { base64 = HeadTextures.TAB_BUYER; name = "Скупка"; hint = "Что магазин купит у вас"; }
            default -> { base64 = HeadTextures.BANKER_INFO; name = "Рейтинг"; hint = "Отзывы покупателей"; }
        }
        boolean selected = t == tab;
        ItemStack item = head(base64, (selected ? "<gold>" : "<white>") + name + (selected ? "</gold>" : "</white>"),
                List.of("", "<gray>" + hint + "</gray>", "", selected ? "<green>▶ Открыто</green>" : "<yellow>ЛКМ </yellow><gray>— открыть</gray>"));
        if (selected && item.getItemMeta() != null) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void renderListings(ListingType type) {
        int[] content = MarketLayout.contentSlots(SIZE);
        int capacity = Math.min(content.length, type == ListingType.SELL ? point.sellSlots() : point.buySlots());
        for (StallListing l : plugin.getTradePointManager().listings(point)) {
            if (l.type() != type || l.slotIndex() >= capacity) continue;
            int slot = content[l.slotIndex()];
            listingAt.put(slot, l);
            inventory.setItem(slot, type == ListingType.SELL ? goodsItem(l) : orderItem(l));
        }
    }

    private ItemStack goodsItem(StallListing l) {
        MarketStyle style = plugin.getMarketStyle();
        ItemStack item = l.template();
        item.setAmount(Math.max(1, Math.min(l.stock(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<gray>Цена за шт.:</gray> " + style.money(l.unitPrice())));
        if (l.stock() > 0) {
            lore.add(MessageUtils.parse(viewer, "<gray>В наличии: <white>" + l.stock() + "</white></gray>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— купить 1</gray>"));
            int many = Math.min(l.stock(), item.getMaxStackSize());
            if (many > 1) lore.add(MessageUtils.parse(viewer, "<yellow>Shift+ЛКМ </yellow><gray>— купить " + many + "</gray>"));
        } else {
            lore.add(MessageUtils.parse(viewer, "<red>Распродано</red>"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack orderItem(StallListing l) {
        MarketStyle style = plugin.getMarketStyle();
        ItemStack item = l.template();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        int have = ItemTransfer.count(viewer, l.template());
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<gray>Магазин платит за шт.:</gray> " + style.money(l.unitPrice())));
        lore.add(MessageUtils.parse(viewer, "<gray>Ещё примет: <white>" + l.freeCapacity() + "</white></gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>У вас с собой: <white>" + have + "</white></gray>"));
        lore.add(Component.empty());
        if (l.freeCapacity() <= 0) {
            lore.add(MessageUtils.parse(viewer, "<red>Заказ выполнен</red>"));
        } else if (point.tillCoins() < l.unitPrice()) {
            lore.add(MessageUtils.parse(viewer, "<red>В кассе магазина не хватает денег</red>"));
        } else if (have <= 0) {
            lore.add(MessageUtils.parse(viewer, "<gray>Нет такого предмета</gray>"));
        } else {
            lore.add(MessageUtils.parse(viewer, "<green>ЛКМ </green><gray>— продать 1</gray>"));
            lore.add(MessageUtils.parse(viewer, "<yellow>Shift+ЛКМ </yellow><gray>— продать всё возможное</gray>"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void renderRating() {
        int[] content = MarketLayout.contentSlots(SIZE);
        RatingSummary s = plugin.getRatingService().summary(point);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ratingLine());
        lore.add("");
        lore.add("<gray>Оценки ставят покупатели, которые");
        lore.add("<gray>реально торговали с этим магазином.");
        inventory.setItem(content[1], head(HeadTextures.BANKER_INFO, "<gold>Рейтинг магазина</gold>", lore));

        List<String> rate = new ArrayList<>();
        rate.add("");
        rate.add("<gray>Оценить можно после сделок на сумму от");
        rate.add("<white>" + plugin.getMarketConfig().ratingMinTrade() + "</white><gray> монет; повторно — раз в <white>"
                + plugin.getMarketConfig().ratingCooldownHours() + "</white><gray> ч.");
        rate.add("");
        rate.add("<green>ЛКМ </green><gray>— поставить оценку</gray>");
        inventory.setItem(content[3], head(HeadTextures.MARKET_OPEN, "<green>Оценить магазин</green>", rate));
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
        Tab[] tabs = Tab.values();
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

        if (tab == Tab.RATING) {
            if (slot == MarketLayout.contentSlots(SIZE)[3]) onRate();
            return;
        }
        StallListing l = listingAt.get(slot);
        if (l == null) return;
        boolean many = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (click != ClickType.LEFT && !many) return;

        if (l.type() == ListingType.SELL) {
            int amount = many ? Math.max(1, Math.min(l.stock(), l.template().getMaxStackSize())) : 1;
            askBuy(l, amount);
        } else {
            int amount = many ? Integer.MAX_VALUE : 1;
            finish(plugin.getTradeService().sell(viewer, point, l.id(), amount), l);
        }
    }

    private void askBuy(StallListing l, int amount) {
        long total;
        try {
            total = Math.multiplyExact(l.unitPrice(), (long) amount);
        } catch (ArithmeticException e) {
            return;
        }
        Tab returnTo = tab;
        if (total < plugin.getMarketConfig().confirmThreshold()) {
            finish(plugin.getTradeService().buy(viewer, point, l.id(), amount), l);
            return;
        }
        List<String> summary = List.of(
                "<gray>Товар: <white>" + l.template().getType().name() + "</white> x<white>" + amount + "</white></gray>",
                "<gray>К оплате:</gray> " + plugin.getMarketStyle().money(total));
        new StallConfirmGui(plugin, viewer, point.claimId(), "<gold>Подтвердите покупку</gold>", summary,
                () -> {
                    finish(plugin.getTradeService().buy(viewer, point, l.id(), amount), l);
                    reopen(returnTo);
                },
                () -> reopen(returnTo)).open();
    }

    private void finish(StallTradeService.Outcome o, StallListing l) {
        var msg = plugin.getMarketMessages();
        String item = l.template().getType().name();
        switch (o.result()) {
            case OK -> {
                if (l.type() == ListingType.SELL) {
                    msg.send(viewer, "trade-bought", "amount", String.valueOf(o.amount()), "item", item,
                            "money", plugin.getMarketStyle().money(o.total()));
                } else {
                    msg.send(viewer, "trade-sold", "amount", String.valueOf(o.amount()), "item", item,
                            "money", plugin.getMarketStyle().money(o.net()), "tax", plugin.getMarketStyle().money(o.tax()));
                }
            }
            case CLOSED -> msg.send(viewer, "stall-closed");
            case SELF -> msg.send(viewer, "trade-self");
            case BAD_REPUTATION -> msg.send(viewer, "outcast-refused");
            case GONE -> msg.send(viewer, "trade-gone");
            case NOT_ENOUGH_STOCK -> msg.send(viewer, "trade-not-enough-stock");
            case NO_MONEY -> msg.send(viewer, "trade-no-money");
            case NO_SPACE -> msg.send(viewer, "trade-no-space");
            case TILL_EMPTY -> msg.send(viewer, "trade-till-empty");
            case ORDER_FULL -> msg.send(viewer, "trade-order-full");
            case NO_ITEMS -> msg.send(viewer, "trade-no-items");
            case ECONOMY_DOWN -> msg.send(viewer, "economy-down");
            case DB_ERROR -> msg.send(viewer, "listing-error");
            case BUSY -> { }
        }
        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) render();
    }

    private void onRate() {
        Tab returnTo = tab;
        Runnable back = () -> reopen(returnTo);
        promptNumber("prompt-stars", 1, 5, back, stars ->
                promptText("prompt-comment", back, comment -> {
                    RatingService.Result r = plugin.getRatingService().rate(viewer, point, (int) stars, comment);
                    var msg = plugin.getMarketMessages();
                    switch (r) {
                        case OK -> msg.send(viewer, "rating-saved");
                        case SELF -> msg.send(viewer, "rating-self");
                        case NOT_TRADED -> msg.send(viewer, "rating-need-trade",
                                "amount", String.valueOf(plugin.getMarketConfig().ratingMinTrade()));
                        case COOLDOWN -> msg.send(viewer, "rating-cooldown",
                                "hours", String.valueOf(plugin.getMarketConfig().ratingCooldownHours()));
                        case INVALID -> msg.send(viewer, "prompt-invalid");
                        case DB_ERROR -> msg.send(viewer, "listing-error");
                    }
                }, "max", String.valueOf(plugin.getMarketConfig().ratingMaxComment())));
    }

    private void reopen(Tab returnTo) {
        if (!viewer.isOnline() || plugin.getChatPromptService().has(viewer)) return;
        if (!point.isTrading() || point.isOwner(viewer.getUniqueId())) return;
        new StallBuyerGui(plugin, viewer, point, returnTo).open();
    }
}
