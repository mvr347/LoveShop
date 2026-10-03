package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.BlacklistEntry;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The point's blacklist (54 slots, gui_gen v2.1, paged): players who cannot trade here. */
public final class StallBlacklistGui extends MarketGui {

    private static final int SIZE = 54;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final TradePoint point;

    public StallBlacklistGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        show(Bukkit.createInventory(this, SIZE, MessageUtils.parse(viewer, t("gui-blacklist-title"))));
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
        List<BlacklistEntry> list = new ArrayList<>();
        try {
            list = plugin.getMarketRepository().loadBlacklist(point.claimId());
        } catch (Exception ignored) {
            // an unreadable list is shown as empty; the actions below report database errors
        }
        int max = plugin.getMarketConfig().blacklistMaxEntries();

        inventory.setItem(0, tile(HeadTextures.BANKER_INFO, "gui-blacklist-head", "gui-blacklist-head-lore",
                "count", String.valueOf(list.size()), "max", String.valueOf(max)));
        if (list.size() < max) {
            // "Add player" is the footer's extra button (slot 51 of 54), not a header control.
            button(MarketLayout.extraSlot(SIZE), tile(HeadTextures.BUTTON_PLUS, "gui-blacklist-add", "gui-blacklist-add-lore"), e -> promptAdd());
        }

        if (list.isEmpty()) emptyCard("gui-blacklist-empty-lore");
        int[] content = MarketLayout.contentSlots(SIZE);
        int start = pager((int) Math.ceil((double) list.size() / content.length)) * content.length;
        for (int i = 0; i < content.length && start + i < list.size(); i++) {
            BlacklistEntry entry = list.get(start + i);
            button(content[i], entryItem(entry), e -> remove(entry));
        }
        footer(() -> new StallManageGui(plugin, viewer, point).open());
        refreshClient();
    }

    private ItemStack entryItem(BlacklistEntry be) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(be.playerUuid());
        meta.setOwningPlayer(op);
        String name = op.getName() != null ? op.getName() : be.playerUuid().toString().substring(0, 8);
        meta.displayName(MessageUtils.parse(viewer, t("gui-blacklist-entry", "player", name)));
        String reason = be.reason() != null && !be.reason().isBlank() ? be.reason() : t("gui-blacklist-no-reason");
        List<Component> lore = new ArrayList<>();
        for (String line : lines("gui-blacklist-entry-lore", "reason", reason,
                "date", WHEN.format(Instant.ofEpochMilli(be.createdAt())))) {
            lore.add(MessageUtils.parse(viewer, line));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void remove(BlacklistEntry be) {
        try {
            plugin.getMarketRepository().removeBlacklist(point.claimId(), be.playerUuid());
            OfflinePlayer op = Bukkit.getOfflinePlayer(be.playerUuid());
            plugin.getMarketMessages().send(viewer, "blacklist-removed", "player", op.getName() != null ? op.getName() : "?");
        } catch (Exception e) {
            plugin.getMarketMessages().send(viewer, "db-error");
        }
        render();
    }

    private void promptAdd() {
        promptPlayer("prompt-blacklist-target", this::open, target -> {
            if (target.getUniqueId().equals(viewer.getUniqueId())) {
                plugin.getMarketMessages().send(viewer, "blacklist-self");
                return;
            }
            promptText("prompt-blacklist-reason", this::open, reason -> {
                try {
                    if (plugin.getMarketRepository().addBlacklist(point.claimId(), target.getUniqueId(), reason,
                            plugin.getMarketConfig().blacklistMaxEntries())) {
                        plugin.getMarketMessages().send(viewer, "blacklist-added", "player", target.getName());
                    } else {
                        plugin.getMarketMessages().send(viewer, "blacklist-full");
                    }
                } catch (Exception e) {
                    plugin.getMarketMessages().send(viewer, "db-error");
                }
                new StallBlacklistGui(plugin, viewer, point).open();
            }, "max", "64");
        });
    }
}
