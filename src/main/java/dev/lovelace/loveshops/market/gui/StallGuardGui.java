package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.GuardService;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Guard of the point (27 slots, gui_gen v2.1): one header control per hiring term, a "dismiss"
 * button in the footer while the guard is on duty.
 */
public final class StallGuardGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;
    private final Runnable back;

    public StallGuardGui(LoveShops plugin, Player viewer, TradePoint point) {
        this(plugin, viewer, point, () -> new StallManageGui(plugin, viewer, point).open());
    }

    /** @param back what the Back button does (the menu this one was opened from) */
    public StallGuardGui(LoveShops plugin, Player viewer, TradePoint point, Runnable back) {
        super(plugin, viewer);
        this.point = point;
        this.back = back;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-guard-title"))));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        frame();
        boolean active = point.guardState() == GuardState.ACTIVE;

        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-guard-head", "gui-guard-head-lore", "status", status()));

        List<Integer> durations = plugin.getMarketConfig().guardDurations();
        long costPerDay = plugin.getMarketConfig().guardCostPerDay();
        List<Control> hire = new ArrayList<>();
        for (int i = 0; i < Math.min(durations.size(), 6); i++) {
            int days = durations.get(i);
            hire.add(new Control(hireButton(days, costPerDay * days, active), e -> hire(days)));
        }
        if (!hire.isEmpty()) controls(hire);

        if (active) {
            button(MarketLayout.extraSlot(SIZE), tile(HeadTextures.MARKET_CLOSED, "gui-guard-fire", "gui-guard-fire-lore"), e -> {
                plugin.getGuardService().fire(viewer, point);
                plugin.getMarketMessages().send(viewer, "guard-fired");
                render();
            });
        }
        footer(back);
        refreshClient();
    }

    private String status() {
        return switch (point.guardState()) {
            case ACTIVE -> t("gui-guard-active", "time", duration(point.guardPaidUntil() - System.currentTimeMillis()));
            case UNPAID -> t("gui-guard-unpaid");
            case NONE -> t("gui-guard-none");
        };
    }

    private ItemStack hireButton(int days, long cost, boolean active) {
        String key = active ? "gui-guard-extend" : "gui-guard-hire";
        return head(HeadTextures.MARKET_OPEN, t(key, "days", String.valueOf(days)),
                lines(key + "-lore", "days", String.valueOf(days), "cost", plugin.getMarketStyle().money(cost)));
    }

    private void hire(int days) {
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
}
