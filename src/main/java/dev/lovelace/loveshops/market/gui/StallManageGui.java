package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.StallUpgradeService;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * "Management" section (27 slots, gui_gen v2.1): trading mode, point upgrade, transfer and
 * open/closed as header controls; discounts, the blacklist and the guard as cards in the work zone.
 */
public final class StallManageGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;

    public StallManageGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-manage-title"))));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        frame();
        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-manage-head", "gui-manage-head-lore",
                "level", String.valueOf(point.level())));

        controls(List.of(
                new Control(modeButton(), e -> cycleMode()),
                new Control(upgradeButton(), e -> upgrade()),
                new Control(tile(HeadTextures.BANKER_DEPOSIT, "gui-manage-transfer", "gui-manage-transfer-lore"), e -> promptTransfer()),
                new Control(toggleButton(), e -> toggleOpen())
        ));

        int discounts = 0;
        int blacklisted = 0;
        try {
            discounts = plugin.getMarketRepository().loadDiscounts(point.claimId()).size();
            blacklisted = plugin.getMarketRepository().loadBlacklist(point.claimId()).size();
        } catch (Exception ignored) {
            // counters are cosmetic; the menus themselves report database trouble
        }
        button(11, tile(HeadTextures.TAB_BUYER, "gui-manage-discounts", "gui-manage-discounts-lore",
                "count", String.valueOf(discounts)), e -> new StallDiscountGui(plugin, viewer, point).open());
        button(13, tile(HeadTextures.MARKET_CLOSED, "gui-manage-blacklist", "gui-manage-blacklist-lore",
                "count", String.valueOf(blacklisted), "max", String.valueOf(plugin.getMarketConfig().blacklistMaxEntries())),
                e -> new StallBlacklistGui(plugin, viewer, point).open());
        button(15, tile(HeadTextures.MARKET_OPEN, "gui-manage-guard", "gui-manage-guard-lore"),
                e -> new StallGuardGui(plugin, viewer, point).open());

        footer(() -> new StallOwnerGui(plugin, viewer, point).open());
        refreshClient();
    }

    private ItemStack modeButton() {
        String mode = t("gui-mode-" + point.tradingMode().name().toLowerCase(Locale.ROOT));
        return tile(HeadTextures.BANKER_INFO, "gui-manage-mode", "gui-manage-mode-lore", "mode", mode);
    }

    private ItemStack upgradeButton() {
        StallUpgradeService svc = plugin.getUpgradeService();
        if (svc.isMax(point)) {
            return tile(HeadTextures.BANKER_DEPOSIT, "gui-manage-upgrade", "gui-manage-upgrade-max-lore",
                    "level", String.valueOf(point.level()), "max", String.valueOf(plugin.getMarketConfig().maxLevel()));
        }
        return tile(HeadTextures.BANKER_DEPOSIT, "gui-manage-upgrade", "gui-manage-upgrade-lore",
                "level", String.valueOf(point.level()), "max", String.valueOf(plugin.getMarketConfig().maxLevel()),
                "shelves", String.valueOf(point.sellSlots()), "orders", String.valueOf(point.buySlots()),
                "storage", String.valueOf(plugin.getTradePointManager().getStorageCapacity(point)),
                "cost", plugin.getMarketStyle().money(svc.nextCost(point)));
    }

    private ItemStack toggleButton() {
        boolean open = point.open();
        return tile(open ? HeadTextures.MARKET_CLOSED : HeadTextures.MARKET_OPEN,
                open ? "gui-manage-close" : "gui-manage-open",
                open ? "gui-manage-close-lore" : "gui-manage-open-lore");
    }

    // ------------------------------------------------------------------ actions

    private void cycleMode() {
        TradingMode next = switch (point.tradingMode()) {
            case SELL_ONLY -> TradingMode.BOTH;
            case BOTH -> TradingMode.BUY_ONLY;
            case BUY_ONLY -> TradingMode.SELL_ONLY;
        };
        point.tradingMode(next);
        plugin.getTradePointManager().save(point);
        plugin.getTradePointManager().updateNpc(point);
        plugin.getTradePointManager().refreshViewers(point.claimId());
        render();
    }

    private void toggleOpen() {
        var res = plugin.getTradePointManager().toggleOpen(viewer, point);
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
        promptPlayer("prompt-transfer-target", this::open, target -> {
            if (target.getUniqueId().equals(viewer.getUniqueId())) {
                plugin.getMarketMessages().send(viewer, "transfer-self");
                return;
            }
            if (plugin.getTradePointManager().transfer(point, target.getUniqueId())) {
                plugin.getMarketMessages().send(viewer, "transfer-done", "player", target.getName());
                plugin.getMarketMessages().send(target, "transfer-received", "player", viewer.getName());
            } else {
                plugin.getMarketMessages().send(viewer, "transfer-failed");
            }
        });
    }
}
