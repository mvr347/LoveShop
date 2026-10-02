package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository.RatingSummary;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.RatingService;
import dev.lovelace.loveshops.market.StallTradeService;
import dev.lovelace.loveshops.market.model.DiscountEntry;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
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
import java.util.Optional;
import java.util.UUID;

/**
 * 27-slot customer GUI at a trade point.
 * Respects tradingMode (BOTH, SELL_ONLY, BUY_ONLY), verifies blacklist,
 * applies personal discounts, and pre-checks rating permissions (canRate).
 */
public final class StallBuyerGui extends MarketGui {

    public enum Tab { GOODS, ORDERS, RATING }

    private final int size;

    private final TradePoint point;
    private Tab tab;
    private final Map<Integer, StallListing> listingAt = new HashMap<>();

    public StallBuyerGui(LoveShops plugin, Player viewer, TradePoint point) {
        this(plugin, viewer, point, point.tradingMode() == TradingMode.BUY_ONLY ? Tab.ORDERS : Tab.GOODS);
    }

    public StallBuyerGui(LoveShops plugin, Player viewer, TradePoint point, Tab tab) {
        super(plugin, viewer);
        this.point = point;
        this.tab = tab;
        // Enough rows for every shelf of the point: 27 shows 7 lots, 36 shows 14.
        this.size = MarketLayout.sizeForContent(Math.max(point.sellSlots(), point.buySlots()));
    }

    public void open() {
        if (plugin.getMarketRepository().isBlacklisted(point.claimId(), viewer.getUniqueId()) && !viewer.hasPermission("loveshops.admin.point")) {
            plugin.getMarketMessages().send(viewer, "blacklist-refused");
            return;
        }
        Component title = MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(point.ownerName()));
        show(Bukkit.createInventory(this, size, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    private List<Tab> availableTabs() {
        TradingMode mode = point.tradingMode();
        List<Tab> tabs = new ArrayList<>();
        if (mode == TradingMode.BOTH || mode == TradingMode.SELL_ONLY) tabs.add(Tab.GOODS);
        if (mode == TradingMode.BOTH || mode == TradingMode.BUY_ONLY) tabs.add(Tab.ORDERS);
        tabs.add(Tab.RATING);
        return tabs;
    }

    @Override
    public void render() {
        if (!point.isTrading()) {
            plugin.getMarketMessages().send(viewer, "stall-closed");
            viewer.closeInventory();
            return;
        }
        if (plugin.getMarketRepository().isBlacklisted(point.claimId(), viewer.getUniqueId()) && !viewer.hasPermission("loveshops.admin.point")) {
            plugin.getMarketMessages().send(viewer, "blacklist-refused");
            viewer.closeInventory();
            return;
        }

        List<Tab> tabs = availableTabs();
        if (!tabs.contains(tab)) {
            tab = tabs.getFirst();
        }

        frame();
        listingAt.clear();

        inventory.setItem(0, ownerHead());

        List<Control> tabControls = new ArrayList<>();
        for (Tab each : tabs) {
            tabControls.add(new Control(tabItem(each), e -> {
                if (each != tab) {
                    tab = each;
                    render();
                }
            }));
        }
        controls(tabControls);
        footer(null);

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
        if (s.count() == 0) return t("gui-main-rating-none");
        return t("gui-main-rating", "star", star, "avg", String.format(Locale.ROOT, "%.1f", s.average()),
                "count", String.valueOf(s.count()));
    }

    private ItemStack ownerHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(point.ownerUuid());
            meta.setOwningPlayer(owner);
            meta.displayName(MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(point.ownerName())));
            List<Component> lore = new ArrayList<>();
            for (String line : lines("gui-customer-head-lore", "rating", ratingLine(), "level", String.valueOf(point.level()))) {
                lore.add(MessageUtils.parse(viewer, line));
            }
            meta.lore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack tabItem(Tab each) {
        String base64;
        String key;
        switch (each) {
            case GOODS -> { base64 = HeadTextures.TAB_SELLER; key = "goods"; }
            case ORDERS -> { base64 = HeadTextures.TAB_BUYER; key = "orders"; }
            default -> { base64 = HeadTextures.BANKER_INFO; key = "rating"; }
        }
        boolean selected = each == tab;
        ItemStack item = head(base64, t("gui-customer-tab-" + key + (selected ? "-on" : "")),
                lines("gui-customer-tab-" + key + "-lore", "state", t(selected ? "gui-customer-tab-open" : "gui-customer-tab-click")));
        if (selected && item.getItemMeta() != null) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void renderListings(ListingType type) {
        int[] content = MarketLayout.contentSlots(size);
        List<StallListing> all;
        try {
            all = plugin.getMarketRepository().listings(point.claimId())
                    .stream().filter(l -> l.type() == type && l.stock() > 0).toList();
        } catch (java.sql.SQLException e) {
            plugin.getLogger().warning("Не удалось загрузить лоты для витрины: " + e.getMessage());
            all = List.of();
        }

        for (int i = 0; i < Math.min(content.length, all.size()); i++) {
            StallListing l = all.get(i);
            int slot = content[i];
            listingAt.put(slot, l);
            inventory.setItem(slot, type == ListingType.SELL ? goodsItem(l) : orderItem(l));
        }
    }

    private ItemStack goodsItem(StallListing l) {
        MarketStyle style = plugin.getMarketStyle();
        ItemStack item = l.template().clone();
        item.setAmount(Math.max(1, Math.min(l.stock(), item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        List<String> extra = new ArrayList<>();
        extra.add("");
        DiscountEntry discount = plugin.getMarketRepository().getDiscount(point.claimId(), viewer.getUniqueId());
        if (discount != null && discount.percent() > 0) {
            long orig = l.unitPrice();
            long off = orig * Math.min(plugin.getMarketConfig().discountMaxPercent(), discount.percent()) / 100L;
            extra.addAll(lines("gui-customer-goods-discount", "price", style.money(orig),
                    "now", style.money(Math.max(1L, orig - off)), "percent", String.valueOf(discount.percent())));
        } else {
            extra.addAll(lines("gui-customer-goods-price", "price", style.money(l.unitPrice())));
        }
        extra.addAll(lines("gui-customer-goods-stock", "stock", String.valueOf(l.stock())));
        for (String line : extra) lore.add(MessageUtils.parse(viewer, line));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack orderItem(StallListing l) {
        ItemStack item = l.template().clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        int needed = Math.max(0, l.maxAmount() - l.stock());
        List<String> extra = new ArrayList<>();
        extra.add("");
        extra.addAll(lines("gui-customer-order", "price", plugin.getMarketStyle().money(l.unitPrice()), "needed", String.valueOf(needed)));
        if (needed > 0) extra.addAll(lines("gui-customer-order-actions"));
        for (String line : extra) lore.add(MessageUtils.parse(viewer, line));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void renderRating() {
        int[] content = MarketLayout.contentSlots(size);
        inventory.setItem(content[1], head(HeadTextures.BANKER_INFO, t("gui-customer-rating"),
                lines("gui-customer-rating-lore", "rating", ratingLine())));

        Optional<RatingService.Result> cannotRate = plugin.getRatingService().canRate(viewer, point);
        if (cannotRate.isPresent()) {
            String reason = switch (cannotRate.get()) {
                case SELF -> t("gui-customer-rate-self");
                case NOT_TRADED -> t("gui-customer-rate-not-traded", "min", plugin.getMarketStyle().money(plugin.getMarketConfig().ratingMinTrade()));
                case COOLDOWN -> t("gui-customer-rate-cooldown", "hours", String.valueOf(plugin.getMarketConfig().ratingCooldownHours()));
                case INVALID -> t("gui-customer-rate-invalid");
                default -> t("gui-customer-rate-unavailable");
            };
            inventory.setItem(content[3], head(HeadTextures.MARKET_CLOSED, t("gui-customer-rate-off"),
                    lines("gui-customer-rate-off-lore", "reason", reason)));
        } else {
            inventory.setItem(content[3], head(HeadTextures.MARKET_OPEN, t("gui-customer-rate"),
                    lines("gui-customer-rate-lore", "min", plugin.getMarketStyle().money(plugin.getMarketConfig().ratingMinTrade()),
                            "hours", String.valueOf(plugin.getMarketConfig().ratingCooldownHours()))));
            actions.put(content[3], e -> onRate());
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= size) return;
        if (actions.containsKey(slot)) {
            super.handleClick(event);
            return;
        }
        if (tab == Tab.RATING) return;

        ClickType click = event.getClick();
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
        var discount = plugin.getMarketRepository().getDiscount(point.claimId(), viewer.getUniqueId());
        if (discount != null && discount.percent() > 0) {
            long discountAmount = total * Math.min(plugin.getMarketConfig().discountMaxPercent(), discount.percent()) / 100L;
            total = Math.max(1L, total - discountAmount);
        }

        Tab returnTo = tab;
        if (total < plugin.getMarketConfig().confirmThreshold()) {
            finish(plugin.getTradeService().buy(viewer, point, l.id(), amount), l);
            return;
        }
        List<String> summary = lines("gui-confirm-buy-summary", "item", l.template().getType().name(),
                "amount", String.valueOf(amount), "total", plugin.getMarketStyle().money(total));
        new StallConfirmGui(plugin, viewer, point.claimId(), t("gui-confirm-buy-title"), summary,
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
            case MODE_DENIED -> msg.send(viewer, "stall-closed");
            case BLACKLISTED -> msg.send(viewer, "blacklist-refused");
            case BUSY -> { }
        }
        if (viewer.getOpenInventory().getTopInventory().getHolder() == this) render();
    }

    private void onRate() {
        Optional<RatingService.Result> cannotRate = plugin.getRatingService().canRate(viewer, point);
        if (cannotRate.isPresent()) {
            var msg = plugin.getMarketMessages();
            switch (cannotRate.get()) {
                case SELF -> msg.send(viewer, "rating-self");
                case NOT_TRADED -> msg.send(viewer, "rating-not-traded", "min", plugin.getMarketStyle().money(plugin.getMarketConfig().ratingMinTrade()));
                case COOLDOWN -> msg.send(viewer, "rating-cooldown", "hours", String.valueOf(plugin.getMarketConfig().ratingCooldownHours()));
                default -> msg.send(viewer, "rating-denied");
            }
            return;
        }

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
                                "amount", plugin.getMarketStyle().money(plugin.getMarketConfig().ratingMinTrade()));
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
