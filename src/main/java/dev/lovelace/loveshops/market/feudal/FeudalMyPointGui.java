package dev.lovelace.loveshops.market.feudal;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.ClaimsLink;
import dev.lovelace.loveshops.market.GuardService;
import dev.lovelace.loveshops.market.RefundMath;
import dev.lovelace.loveshops.market.gui.MarketGui;
import dev.lovelace.loveshops.market.gui.StallConfirmGui;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * "Управление арендой" (27 slots, gui_gen v2.1):
 * - Slot 0: Info tile
 * - Slot 2: Аренда (LMB +1 day, Shift+LMB +7 days; shows remaining time, 1d price, 7d price; overdue texture & warning with +40% penalty)
 * - Slot 4: Стража (LMB +1 day, Shift+LMB +7 days; shows remaining time, 1d price, 7d price; inactive texture when unpaid)
 * - Slot 6: Продать торговую точку феодалу (75% refund of prepaid days + all resources to returns)
 * - Footer: Slot 25 Back (to FeudalListGui), Slot 26 Close
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
        boolean inGrace = claims().inGrace(point.claimId()) || left <= 0;
        String time = left > 0 ? duration(left) : t("gui-feudal-overdue");

        String guardStatus = switch (point.guardState()) {
            case ACTIVE -> t("gui-guard-active", "time", duration(point.guardPaidUntil() - System.currentTimeMillis()));
            case UNPAID -> t("gui-guard-unpaid");
            case NONE -> t("gui-guard-none");
        };

        // 0 слот — информация
        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-feudal-mine-head", "gui-feudal-mine-head-lore",
                "id", manager.nameOf(point), "time", time, "guard", guardStatus));

        // 1. Аренда: ЛКМ +1 день, Shift+ЛКМ +7 дней
        long dayCost = claims().dayCost(point.claimId());
        if (dayCost <= 0) dayCost = Math.max(1L, claims().renewCost(point.claimId()) / 7L);
        if (inGrace) {
            dayCost += (long) Math.ceil(dayCost * 0.40);
        }
        long weekCost = dayCost * 7;
        String costDayStr = plugin.getMarketStyle().money(dayCost);
        String costWeekStr = plugin.getMarketStyle().money(weekCost);

        String rentTexture = inGrace ? HeadTextures.FEUDAL_RENT_OVERDUE : HeadTextures.FEUDAL_RENT_NORMAL;
        ItemStack rentItem = inGrace
                ? tile(rentTexture, "gui-feudal-rent-overdue-btn", "gui-feudal-rent-overdue-lore",
                "time", time, "cost_day", costDayStr, "cost_week", costWeekStr)
                : tile(rentTexture, "gui-feudal-rent-btn", "gui-feudal-rent-btn-lore",
                "time", time, "cost_day", costDayStr, "cost_week", costWeekStr);

        // 2. Стража: ЛКМ +1 день, Shift+ЛКМ +7 дней
        boolean guardActive = point.guardState() == GuardState.ACTIVE && point.guardPaidUntil() > System.currentTimeMillis();
        long guardLeft = point.guardPaidUntil() - System.currentTimeMillis();
        String guardTime = guardActive ? duration(guardLeft) : t("gui-guard-unpaid");
        long guardDayCost = plugin.getMarketConfig().guardCostPerDay();
        long guardWeekCost = guardDayCost * 7;
        String guardDayStr = plugin.getMarketStyle().money(guardDayCost);
        String guardWeekStr = plugin.getMarketStyle().money(guardWeekCost);

        String guardTexture = guardActive ? HeadTextures.FEUDAL_GUARD_ACTIVE : HeadTextures.FEUDAL_GUARD_INACTIVE;
        ItemStack guardItem = tile(guardTexture, "gui-feudal-guard-btn", "gui-feudal-guard-btn-lore",
                "time", guardTime, "cost_day", guardDayStr, "cost_week", guardWeekStr, "status", guardStatus);

        // 3. Продать торговую точку феодалу (75% возврат + ресурсы)
        long refund = refund();
        int percent = plugin.getMarketConfig().feudalRefundPercent();
        ItemStack sellItem = tile(HeadTextures.MARKET_CLOSED, "gui-feudal-sell-point", "gui-feudal-sell-point-lore",
                "refund", plugin.getMarketStyle().money(refund), "percent", String.valueOf(percent));

        controls(List.of(
                new Control(rentItem, this::extendRent),
                new Control(guardItem, this::extendGuard),
                new Control(sellItem, e -> confirmReturn())
        ));

        footer(() -> new FeudalListGui(plugin, viewer).open());
        refreshClient();
    }

    private long refund() {
        long left = plugin.getTradePointManager().rentEnd(point) - System.currentTimeMillis();
        long price = plugin.getTradePointManager().infoOf(point).map(ClaimsLink.PointInfo::price).orElse(0L);
        return RefundMath.refund(price, left, claims().periodMillis(), plugin.getMarketConfig().feudalRefundPercent());
    }

    private void extendRent(InventoryClickEvent event) {
        int days = event.getClick().isShiftClick() ? 7 : 1;
        ClaimsLink.RentResult res = claims().extendDays(viewer, point.claimId(), days);
        switch (res.status()) {
            case OK -> plugin.getMarketMessages().send(viewer, "feudal-extended-days", "days", String.valueOf(days),
                    "cost", plugin.getMarketStyle().money(res.cost()));
            case NO_FUNDS -> plugin.getMarketMessages().send(viewer, "feudal-no-funds", "cost", plugin.getMarketStyle().money(res.cost()));
            case NO_ECONOMY -> plugin.getMarketMessages().send(viewer, "economy-down");
            case BAD_PERIODS -> plugin.getMarketMessages().send(viewer, "feudal-prepaid-max");
            default -> plugin.getMarketMessages().send(viewer, "listing-error");
        }
        render();
    }

    private void extendGuard(InventoryClickEvent event) {
        int days = event.getClick().isShiftClick() ? 7 : 1;
        GuardService.Result res = plugin.getGuardService().hire(viewer, point, days);
        var msg = plugin.getMarketMessages();
        switch (res) {
            case OK -> msg.send(viewer, "guard-hired");
            case NO_MONEY -> msg.send(viewer, "guard-no-money");
            case DISABLED -> msg.send(viewer, "guard-disabled");
            case NOT_OWNER -> msg.send(viewer, "not-owner");
            default -> msg.send(viewer, "listing-error");
        }
        render();
    }

    private void confirmReturn() {
        long refund = refund();
        List<String> summary = lines("gui-feudal-sell-summary", "id", plugin.getTradePointManager().nameOf(point),
                "refund", plugin.getMarketStyle().money(refund), "percent", String.valueOf(plugin.getMarketConfig().feudalRefundPercent()));
        new StallConfirmGui(plugin, viewer, point.claimId(), t("gui-feudal-sell-title"), summary,
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
        viewer.closeInventory();
    }
}
