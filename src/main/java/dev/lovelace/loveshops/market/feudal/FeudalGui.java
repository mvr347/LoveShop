package dev.lovelace.loveshops.market.feudal;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.gui.MarketGui;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The landlord's main menu (27 slots, gui_gen v2.1): the list of trade points to rent and, for a
 * tenant, "my point" (prolong, guard, hand back).
 */
public final class FeudalGui extends MarketGui {

    private static final int SIZE = 27;

    public FeudalGui(LoveShops plugin, Player viewer) {
        super(plugin, viewer);
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-feudal-title"))));
    }

    @Override
    public UUID pointId() {
        return null;
    }

    @Override
    public void render() {
        frame();
        var manager = plugin.getTradePointManager();
        Optional<TradePoint> mine = manager.byOwner(viewer.getUniqueId());

        inventory.setItem(0, tile(HeadTextures.TAB_SELLER, "gui-feudal-head", "gui-feudal-head-lore"));

        List<Control> controls = new ArrayList<>();
        controls.add(new Control(tile(HeadTextures.MARKET_OPEN, "gui-feudal-list", "gui-feudal-list-lore",
                "free", String.valueOf(freeCount()), "total", String.valueOf(manager.all().size())),
                e -> new FeudalListGui(plugin, viewer).open()));
        mine.ifPresent(point -> controls.add(new Control(tile(HeadTextures.BANKER_ACCOUNT, "gui-feudal-mine", "gui-feudal-mine-lore",
                "id", manager.nameOf(point)), e -> new FeudalMyPointGui(plugin, viewer, point).open())));
        controls(controls);

        if (mine.isEmpty()) {
            inventory.setItem(13, tile(HeadTextures.BANKER_INFO, "gui-feudal-card-none", "gui-feudal-card-none-lore"));
        } else {
            TradePoint point = mine.get();
            inventory.setItem(13, tile(HeadTextures.BANKER_INFO, "gui-feudal-card-mine", "gui-feudal-card-mine-lore",
                    "id", manager.nameOf(point), "time", rentLeft(point)));
        }
        footer(null);
        refreshClient();
    }

    private int freeCount() {
        int free = 0;
        for (TradePoint p : plugin.getTradePointManager().all()) if (!p.hasOwner()) free++;
        return free;
    }

    private String rentLeft(TradePoint point) {
        long left = plugin.getTradePointManager().rentEnd(point) - System.currentTimeMillis();
        return left > 0 ? duration(left) : t("gui-feudal-overdue");
    }
}
