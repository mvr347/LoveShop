package dev.lovelace.loveshops.market.feudal;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.ClaimsLink;
import dev.lovelace.loveshops.market.gui.MarketGui;
import dev.lovelace.loveshops.market.gui.MarketLayout;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Every trade point with its state (54 slots, gui_gen v2.1, paged): a free one can be rented. */
public final class FeudalListGui extends MarketGui {

    private static final int SIZE = 54;

    public FeudalListGui(LoveShops plugin, Player viewer) {
        super(plugin, viewer);
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-feudal-list-title"))));
    }

    @Override
    public UUID pointId() {
        return null;
    }

    @Override
    public void render() {
        frame();
        var manager = plugin.getTradePointManager();
        List<TradePoint> points = manager.all().stream()
                .sorted(Comparator.comparing(manager::nameOf, String.CASE_INSENSITIVE_ORDER)).toList();

        inventory.setItem(0, tile(HeadTextures.TAB_SELLER, "gui-feudal-list-head", "gui-feudal-list-head-lore",
                "total", String.valueOf(points.size())));

        int[] content = MarketLayout.contentSlots(SIZE);
        int start = pager((int) Math.ceil((double) points.size() / content.length)) * content.length;
        for (int i = 0; i < content.length && start + i < points.size(); i++) {
            TradePoint point = points.get(start + i);
            button(content[i], pointTile(point), e -> {
                if (point.hasOwner()) {
                    plugin.getMarketMessages().send(viewer, "feudal-taken", "id", manager.nameOf(point));
                } else {
                    new FeudalRentGui(plugin, viewer, point).open();
                }
            });
        }
        footer(() -> new FeudalGui(plugin, viewer).open());
        refreshClient();
    }

    private ItemStack pointTile(TradePoint point) {
        var manager = plugin.getTradePointManager();
        String id = manager.nameOf(point);
        long price = manager.infoOf(point).map(ClaimsLink.PointInfo::price).orElse(0L);
        String money = plugin.getMarketStyle().money(price);
        if (!point.hasOwner()) {
            return head(HeadTextures.MARKET_OPEN, t("gui-feudal-point-free", "id", id),
                    lines("gui-feudal-point-free-lore", "price", money, "level", String.valueOf(point.level())));
        }
        long left = manager.rentEnd(point) - System.currentTimeMillis();
        String time = left > 0 ? duration(left) : t("gui-feudal-overdue");
        return head(HeadTextures.MARKET_CLOSED, t("gui-feudal-point-taken", "id", id),
                lines("gui-feudal-point-taken-lore", "owner", point.ownerName() == null ? "?" : point.ownerName(),
                        "time", time, "price", money));
    }
}
