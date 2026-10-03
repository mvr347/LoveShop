package dev.lovelace.loveshops.market.feudal;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.ClaimsLink;
import dev.lovelace.loveshops.market.RefundMath;
import dev.lovelace.loveshops.market.gui.MarketGui;
import dev.lovelace.loveshops.market.gui.StallConfirmGui;
import dev.lovelace.loveshops.market.gui.StallGuardGui;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.UUID;

/**
 * "My point" at the landlord (27 slots, gui_gen v2.1): prolong the rent, hire the guard, hand the
 * point back for a part of the unspent rent.
 */
public final class FeudalMyPointGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;

    public FeudalMyPointGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-feudal-mine-title"))));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    private ClaimsLink claims() {
        return plugin.getTradePointManager().claimsLink();
    }

    @Override
    public void render() {
        frame();
        var manager = plugin.getTradePointManager();
        long left = manager.rentEnd(point) - System.currentTimeMillis();
        String time = left > 0 ? duration(left) : t("gui-feudal-overdue");
        int canAdd = claims().maxExtendPeriods(point.claimId());
        long weekDays = Math.max(1L, claims().periodMillis() / 86_400_000L);

        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-feudal-mine-head", "gui-feudal-mine-head-lore",
                "id", manager.nameOf(point), "time", time));

        String guardStatus = switch (point.guardState()) {
            case ACTIVE -> t("gui-guard-active", "time", duration(point.guardPaidUntil() - System.currentTimeMillis()));
            case UNPAID -> t("gui-guard-unpaid");
            case NONE -> t("gui-guard-none");
        };
        controls(List.of(
                new Control(tile(HeadTextures.BUTTON_PLUS, "gui-feudal-extend", "gui-feudal-extend-lore",
                        "cost", plugin.getMarketStyle().money(claims().rentCost(point.claimId(), 1)),
                        "days", String.valueOf(weekDays), "available", String.valueOf(canAdd)), this::extend),
                new Control(tile(HeadTextures.MARKET_OPEN, "gui-feudal-guard", "gui-feudal-guard-lore", "status", guardStatus),
                        e -> new StallGuardGui(plugin, viewer, point, () -> new FeudalMyPointGui(plugin, viewer, point).open()).open()),
                new Control(tile(HeadTextures.MARKET_CLOSED, "gui-feudal-return", "gui-feudal-return-lore",
                        "refund", plugin.getMarketStyle().money(refund()), "percent", String.valueOf(plugin.getMarketConfig().feudalRefundPercent())),
                        e -> confirmReturn())
        ));
        footer(() -> new FeudalGui(plugin, viewer).open());
        refreshClient();
    }

    private long refund() {
        long left = plugin.getTradePointManager().rentEnd(point) - System.currentTimeMillis();
        long price = plugin.getTradePointManager().infoOf(point).map(ClaimsLink.PointInfo::price).orElse(0L);
        return RefundMath.refund(price, left, claims().periodMillis(), plugin.getMarketConfig().feudalRefundPercent());
    }

    private void extend(InventoryClickEvent event) {
        int available = claims().maxExtendPeriods(point.claimId());
        if (available < 1) {
            plugin.getMarketMessages().send(viewer, "feudal-prepaid-max");
            return;
        }
        int periods = event.getClick().isShiftClick() ? available : 1;
        ClaimsLink.RentResult res = claims().extend(viewer, point.claimId(), periods);
        switch (res.status()) {
            case OK -> plugin.getMarketMessages().send(viewer, "feudal-extended", "weeks", String.valueOf(periods),
                    "cost", plugin.getMarketStyle().money(res.cost()));
            case NO_FUNDS -> plugin.getMarketMessages().send(viewer, "feudal-no-funds", "cost", plugin.getMarketStyle().money(res.cost()));
            case NO_ECONOMY -> plugin.getMarketMessages().send(viewer, "economy-down");
            case BAD_PERIODS -> plugin.getMarketMessages().send(viewer, "feudal-prepaid-max");
            default -> plugin.getMarketMessages().send(viewer, "listing-error");
        }
        render();
    }

    private void confirmReturn() {
        long refund = refund();
        List<String> summary = lines("gui-feudal-return-summary", "id", plugin.getTradePointManager().nameOf(point),
                "refund", plugin.getMarketStyle().money(refund), "percent", String.valueOf(plugin.getMarketConfig().feudalRefundPercent()));
        new StallConfirmGui(plugin, viewer, point.claimId(), t("gui-feudal-return-title"), summary,
                () -> giveBack(refund), () -> new FeudalMyPointGui(plugin, viewer, point).open()).open();
    }

    private void giveBack(long refundAtConfirm) {
        var manager = plugin.getTradePointManager();
        if (!point.isOwner(viewer.getUniqueId())) {
            plugin.getMarketMessages().send(viewer, "not-owner");
            return;
        }
        // Recomputed now: the rent may have been prolonged while the confirmation was open.
        long refund = refund();
        claims().release(point.claimId(), "ABANDONED");
        manager.refundToReturns(viewer.getUniqueId(), refund, "refund-landlord");
        plugin.getMarketMessages().send(viewer, "feudal-returned", "id", manager.nameOf(point),
                "refund", plugin.getMarketStyle().money(refund));
    }
}
