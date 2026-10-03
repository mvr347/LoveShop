package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.TradePointManager;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * "Trade" section (27 slots, gui_gen v2.1): Sell, Buy and the till as buttons of the work zone. Buying is
 * switched on in Management (the default trading mode is sell-only), until then its button only
 * explains how to turn it on.
 */
public final class StallTradeMenuGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;

    public StallTradeMenuGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-trade-title"))));
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
        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-trade-head", "gui-trade-head-lore",
                "mode", t("gui-mode-" + point.tradingMode().name().toLowerCase(java.util.Locale.ROOT))));

        boolean sellOn = point.tradingMode() != TradingMode.BUY_ONLY;
        boolean buyOn = point.tradingMode() != TradingMode.SELL_ONLY;
        rowButtons(MarketLayout.workStart(SIZE), List.of(
                new Control(sellOn
                        ? tile(HeadTextures.BANKER_DEPOSIT, "gui-trade-sell", "gui-trade-sell-lore")
                        : tile(HeadTextures.MARKET_CLOSED, "gui-trade-sell-off", "gui-trade-sell-off-lore"),
                        e -> {
                            if (sellOn) new StallOwnerSellGui(plugin, viewer, point).open();
                            else plugin.getMarketMessages().send(viewer, "trade-mode-sell-off");
                        }),
                new Control(buyOn
                        ? tile(HeadTextures.BANKER_WITHDRAW, "gui-trade-buy", "gui-trade-buy-lore")
                        : tile(HeadTextures.MARKET_CLOSED, "gui-trade-buy-off", "gui-trade-buy-off-lore"),
                        e -> {
                            if (buyOn) new StallOwnerBuyGui(plugin, viewer, point).open();
                            else plugin.getMarketMessages().send(viewer, "trade-mode-buy-off");
                        }),
                new Control(tile(HeadTextures.BANKER_ACCOUNT, "gui-trade-till", "gui-trade-till-lore",
                        "money", plugin.getMarketStyle().money(point.tillCoins())), this::clickTill)
        ));
        footer(() -> new StallOwnerGui(plugin, viewer, point).open());
        refreshClient();
    }

    private void clickTill(InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            depositTill();
        } else {
            withdrawTill();
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
            plugin.getMarketMessages().send(viewer, "till-need-coins");
            return;
        }
        long value = eco.valueOf(hand);
        if (value <= 0) return;
        if (eco.charge(viewer, value)) {
            point.tillCoins(point.tillCoins() + value);
            plugin.getTradePointManager().save(point);
            plugin.getMarketMessages().send(viewer, "till-deposited", "money", plugin.getMarketStyle().money(value));
            render();
        }
    }
}
