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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 27-slot Blacklist Management GUI for trade point owner.
 */
public final class StallBlacklistGui extends MarketGui {

    private static final int SIZE = 27;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final TradePoint point;
    private int page = 0;
    private final Map<Integer, BlacklistEntry> entryAt = new HashMap<>();

    public StallBlacklistGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<red>Торговая точка — чёрный список</red>");
        show(Bukkit.createInventory(this, SIZE, title));
    }

    @Override
    public UUID pointId() {
        return point.claimId();
    }

    @Override
    public void render() {
        MarketLayout.frame(inventory);
        entryAt.clear();

        List<BlacklistEntry> list = new ArrayList<>();
        try {
            list = plugin.getMarketRepository().loadBlacklist(point.claimId());
        } catch (Exception ignored) {}

        int maxEntries = plugin.getMarketConfig().blacklistMaxEntries();

        // Header
        inventory.setItem(0, headerHead(list.size(), maxEntries));
        if (list.size() < maxEntries) {
            inventory.setItem(4, head(HeadTextures.BUTTON_PLUS, "<green>Добавить игрока в ЧС</green>", List.of(
                    "",
                    "<gray>Запретить игроку торговать с этой точкой.</gray>",
                    "",
                    "<green>ЛКМ </green><gray>— ввести ник игрока</gray>"
            )));
        }

        // Work zone
        int[] content = MarketLayout.contentSlots(SIZE);
        int perPage = content.length;
        int totalPages = Math.max(1, (int) Math.ceil((double) list.size() / perPage));
        if (page >= totalPages) page = totalPages - 1;

        int start = page * perPage;
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx < list.size()) {
                BlacklistEntry be = list.get(idx);
                int slot = content[i];
                inventory.setItem(slot, formatEntry(be));
                entryAt.put(slot, be);
            }
        }

        // Footer
        if (page > 0) {
            inventory.setItem(MarketLayout.extraSlot(SIZE) - 1, head(HeadTextures.BUTTON_ARROW_LEFT, "<yellow>Предыдущая страница</yellow>",
                    List.of("", "<gray>Страница " + page + " / " + totalPages + "</gray>", "<yellow>ЛКМ </yellow><gray>— назад</gray>")));
        }
        if (page < totalPages - 1) {
            inventory.setItem(MarketLayout.extraSlot(SIZE), head(HeadTextures.BUTTON_ARROW_RIGHT, "<yellow>Следующая страница</yellow>",
                    List.of("", "<gray>Страница " + (page + 2) + " / " + totalPages + "</gray>", "<yellow>ЛКМ </yellow><gray>— вперёд</gray>")));
        }

        inventory.setItem(MarketLayout.backSlot(SIZE), head(HeadTextures.BUTTON_BACK, "<yellow>Назад</yellow>",
                List.of("", "<gray>В главное меню точки</gray>", "<yellow>ЛКМ </yellow><gray>— вернуться</gray>")));
        inventory.setItem(MarketLayout.closeSlot(SIZE), head(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        refreshClient();
    }

    private ItemStack headerHead(int count, int max) {
        return head(HeadTextures.BANKER_INFO, "<red>Чёрный список</red>", List.of(
                "",
                "<gray>Игроков в списке: <white>" + count + "</white> / <white>" + max + "</white></gray>",
                "",
                "<gray>Игроки из этого списка не могут</gray>",
                "<gray>покупать товары на вашей витрине</gray>",
                "<gray>и сдавать предметы по вашим ордерам.</gray>"
        ));
    }

    private ItemStack formatEntry(BlacklistEntry be) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(be.playerUuid());
        meta.setOwningPlayer(op);
        String name = op.getName() != null ? op.getName() : be.playerUuid().toString().substring(0, 8);
        meta.displayName(MessageUtils.parse(viewer, "<red>" + name + "</red>"));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<gray>Причина: <white>" + (be.reason() != null && !be.reason().isBlank() ? be.reason() : "не указана") + "</white></gray>"));
        lore.add(MessageUtils.parse(viewer, "<gray>Добавлен: <white>" + WHEN.format(Instant.ofEpochMilli(be.createdAt())) + "</white></gray>"));
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<red>ЛКМ </red><gray>— удалить из чёрного списка</gray>"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();

        if (rawSlot == MarketLayout.closeSlot(SIZE)) {
            viewer.closeInventory();
            return;
        }

        if (rawSlot == MarketLayout.backSlot(SIZE)) {
            new StallOwnerGui(plugin, viewer, point).open();
            return;
        }

        if (rawSlot == 4) {
            promptAdd();
            return;
        }

        int prevSlot = MarketLayout.extraSlot(SIZE) - 1;
        int nextSlot = MarketLayout.extraSlot(SIZE);
        if (rawSlot == prevSlot && page > 0) {
            page--;
            render();
            return;
        }
        if (rawSlot == nextSlot) {
            page++;
            render();
            return;
        }

        BlacklistEntry be = entryAt.get(rawSlot);
        if (be != null) {
            try {
                plugin.getMarketRepository().removeBlacklist(point.claimId(), be.playerUuid());
                OfflinePlayer op = Bukkit.getOfflinePlayer(be.playerUuid());
                viewer.sendMessage(MessageUtils.parse(viewer, "<green>Игрок " + (op.getName() != null ? op.getName() : "") + " удалён из чёрного списка.</green>"));
            } catch (Exception e) {
                viewer.sendMessage(MessageUtils.parse(viewer, "<red>Ошибка при удалении из чёрного списка.</red>"));
            }
            render();
        }
    }

    private void promptAdd() {
        viewer.closeInventory();
        promptPlayer("prompt-blacklist-target", this::open, target -> {
            if (target.getUniqueId().equals(viewer.getUniqueId())) {
                viewer.sendMessage(MessageUtils.parse(viewer, "<red>Нельзя добавить себя в чёрный список!</red>"));
                return;
            }
            promptText("prompt-blacklist-reason", this::open, reason -> {
                try {
                    if (plugin.getMarketRepository().addBlacklist(point.claimId(), target.getUniqueId(), reason,
                            plugin.getMarketConfig().blacklistMaxEntries())) {
                        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Игрок " + target.getName() + " добавлен в чёрный список.</green>"));
                    } else {
                        viewer.sendMessage(MessageUtils.parse(viewer, "<red>Чёрный список заполнен.</red>"));
                    }
                } catch (Exception e) {
                    viewer.sendMessage(MessageUtils.parse(viewer, "<red>Ошибка при добавлении в чёрный список.</red>"));
                }
                new StallBlacklistGui(plugin, viewer, point).open();
            }, "max", "64");
        });
    }
}
