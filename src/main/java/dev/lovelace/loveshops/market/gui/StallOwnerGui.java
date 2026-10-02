package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository.RatingSummary;
import dev.lovelace.loveshops.market.MarketStyle;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Main menu of the owner's trade point (27 slots, gui_gen v2.1): three sections as header
 * controls - Trade (sell, buy, till), Storage, Management - and the state of the point as
 * read-only cards in the work zone.
 */
public final class StallOwnerGui extends MarketGui {

    private static final int SIZE = 27;

    private final TradePoint point;

    public StallOwnerGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE,
                MessageUtils.parse(viewer, plugin.getMarketStyle().stallTitle(viewer.getName()))));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        frame();
        inventory.setItem(0, ownerHead());

        int cap = plugin.getTradePointManager().getStorageCapacity(point);
        int stored = plugin.getTradePointManager().getStorage(point).size();
        controls(List.of(
                new Control(tile(HeadTextures.BANKER_DEPOSIT, "gui-main-trade", "gui-main-trade-lore"),
                        e -> new StallTradeMenuGui(plugin, viewer, point).open()),
                new Control(tile(HeadTextures.BANKER_WITHDRAW, "gui-main-storage", "gui-main-storage-lore",
                        "stored", String.valueOf(stored), "capacity", String.valueOf(cap)),
                        e -> new StallStorageGui(plugin, viewer, point).open()),
                new Control(tile(HeadTextures.BANKER_INFO, "gui-main-manage", "gui-main-manage-lore"),
                        e -> new StallManageGui(plugin, viewer, point).open())
        ));

        inventory.setItem(11, tile(HeadTextures.BANKER_ACCOUNT, "gui-main-card-till", "gui-main-card-till-lore",
                "money", plugin.getMarketStyle().money(point.tillCoins())));
        inventory.setItem(13, rentCard());
        inventory.setItem(15, tile(HeadTextures.MARKET_OPEN, "gui-main-card-guard", "gui-main-card-guard-lore",
                "status", guardStatus()));

        footer(null);
        refreshClient();
    }

    private ItemStack ownerHead() {
        ItemStack item = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(viewer);
        meta.displayName(MessageUtils.parse(viewer, t("gui-main-head", "player", viewer.getName())));
        List<Component> lore = new ArrayList<>();
        RatingSummary rating = plugin.getRatingService().summary(point);
        String star = plugin.getMarketStyle().icon(MarketStyle.Icon.STAR);
        String ratingLine = rating.count() > 0
                ? t("gui-main-rating", "star", star, "avg", String.format(Locale.ROOT, "%.1f", rating.average()),
                        "count", String.valueOf(rating.count()))
                : t("gui-main-rating-none");
        for (String line : lines("gui-main-head-lore",
                "level", String.valueOf(point.level()),
                "status", t(point.open() ? "gui-status-open" : "gui-status-closed"),
                "rating", ratingLine)) {
            lore.add(MessageUtils.parse(viewer, line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack rentCard() {
        long end = plugin.getTradePointManager().rentEnd(point);
        long left = end - System.currentTimeMillis();
        String when = end <= 0 ? t("gui-main-rent-unknown")
                : left > 0 ? t("gui-main-rent-left", "time", duration(left))
                : t("gui-main-rent-grace");
        return tile(HeadTextures.TAB_SELLER, "gui-main-card-rent", "gui-main-card-rent-lore", "when", when);
    }

    private String guardStatus() {
        GuardState state = point.guardState();
        return switch (state) {
            case ACTIVE -> t("gui-guard-active", "time", duration(point.guardPaidUntil() - System.currentTimeMillis()));
            case UNPAID -> t("gui-guard-unpaid");
            case NONE -> t("gui-guard-none");
        };
    }
}
