package dev.lovelace.loveshops.market.feudal;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.ClaimsLink;
import dev.lovelace.loveshops.market.gui.MarketGui;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Renting a free point (27 slots, gui_gen v2.1). One week is already set; the number of weeks goes
 * up with the left click and down with the right one, up to what the server allows to prepay.
 */
public final class FeudalRentGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;
    private int weeks = 1;

    public FeudalRentGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-feudal-rent-title"))));
    }

    @Override
    public UUID pointId() {
        return null;
    }

    private ClaimsLink claims() {
        return plugin.getTradePointManager().claimsLink();
    }

    @Override
    public void render() {
        frame();
        var manager = plugin.getTradePointManager();
        String id = manager.nameOf(point);
        int max = claims().maxRentPeriods();
        if (weeks > max) weeks = max;
        long cost = claims().rentCost(point.claimId(), weeks);
        long periodDays = Math.max(1L, claims().periodMillis() / 86_400_000L);

        inventory.setItem(0, tile(HeadTextures.MARKET_OPEN, "gui-feudal-rent-head", "gui-feudal-rent-head-lore",
                "id", id, "level", String.valueOf(point.level())));

        List<String> costLines = new ArrayList<>(CoinFormat.glyphLineStrings(plugin.getEconomy().orElse(null), cost));
        List<String> confirmLore = new ArrayList<>(lines("gui-feudal-rent-confirm-top", "id", id,
                "weeks", String.valueOf(weeks), "days", String.valueOf(weeks * periodDays)));
        confirmLore.addAll(costLines);
        confirmLore.addAll(lines("gui-feudal-rent-confirm-bottom"));

        rowButtons(9, List.of(
                new Control(head(HeadTextures.BUTTON_PLUS, t("gui-feudal-rent-weeks"),
                        lines("gui-feudal-rent-weeks-lore", "weeks", String.valueOf(weeks), "max", String.valueOf(max),
                                "days", String.valueOf(weeks * periodDays))), this::clickWeeks),
                new Control(head(HeadTextures.HEAD_CONFIRM, t("gui-feudal-rent-confirm"), confirmLore), e -> rent())
        ));
        footer(() -> new FeudalListGui(plugin, viewer).open());
        refreshClient();
    }

    private void clickWeeks(InventoryClickEvent event) {
        int max = claims().maxRentPeriods();
        if (event.getClick().isRightClick()) {
            weeks = Math.max(1, weeks - 1);
        } else {
            weeks = Math.min(max, weeks + 1);
        }
        render();
    }

    private void rent() {
        if (point.hasOwner()) {
            plugin.getMarketMessages().send(viewer, "feudal-taken", "id", plugin.getTradePointManager().nameOf(point));
            new FeudalListGui(plugin, viewer).open();
            return;
        }
        ClaimsLink.RentResult res = claims().rent(viewer, point.claimId(), weeks);
        switch (res.status()) {
            case OK -> {
                viewer.closeInventory();
                plugin.getMarketMessages().send(viewer, "feudal-rented", "id", plugin.getTradePointManager().nameOf(point),
                        "weeks", String.valueOf(weeks), "cost", plugin.getMarketStyle().money(res.cost()));
            }
            case DENIED -> {
                if (res.message() != null) viewer.sendMessage(res.message());
                viewer.closeInventory();
            }
            case NO_FUNDS -> plugin.getMarketMessages().send(viewer, "feudal-no-funds", "cost", plugin.getMarketStyle().money(res.cost()));
            case NO_ECONOMY -> plugin.getMarketMessages().send(viewer, "economy-down");
            case BAD_PERIODS -> plugin.getMarketMessages().send(viewer, "feudal-bad-weeks");
            default -> plugin.getMarketMessages().send(viewer, "listing-error");
        }
    }
}
