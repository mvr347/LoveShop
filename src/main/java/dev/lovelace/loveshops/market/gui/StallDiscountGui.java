package dev.lovelace.loveshops.market.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.DiscountEntry;
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
 * 27-slot Discount Management GUI for trade point owner.
 */
public final class StallDiscountGui extends MarketGui {

    private static final int SIZE = 27;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final TradePoint point;
    private int page = 0;
    private final Map<Integer, DiscountEntry> entryAt = new HashMap<>();

    public StallDiscountGui(LoveShops plugin, Player viewer, TradePoint point) {
        super(plugin, viewer);
        this.point = point;
    }

    public void open() {
        Component title = MessageUtils.parse(viewer, "<gold>Торговая точка — скидки</gold>");
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

        List<DiscountEntry> list = new ArrayList<>();
        try {
            list = plugin.getMarketRepository().loadDiscounts(point.claimId())
                    .stream().filter(d -> !d.isExpired()).toList();
        } catch (Exception ignored) {}

        // Header
        inventory.setItem(0, headerHead(list.size()));
        inventory.setItem(4, head(HeadTextures.BUTTON_PLUS, "<green>Выдать скидку</green>", List.of(
                "",
                "<gray>Установить персональную скидку покупателю.</gray>",
                "",
                "<green>ЛКМ </green><gray>— выбрать игрока и размер скидки</gray>"
        )));

        // Work zone
        int[] content = MarketLayout.contentSlots(SIZE);
        int perPage = content.length;
        int totalPages = Math.max(1, (int) Math.ceil((double) list.size() / perPage));
        if (page >= totalPages) page = totalPages - 1;

        int start = page * perPage;
        for (int i = 0; i < perPage; i++) {
            int idx = start + i;
            if (idx < list.size()) {
                DiscountEntry de = list.get(idx);
                int slot = content[i];
                inventory.setItem(slot, formatEntry(de));
                entryAt.put(slot, de);
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

    private ItemStack headerHead(int count) {
        return head(HeadTextures.BANKER_INFO, "<gold>Персональные скидки</gold>", List.of(
                "",
                "<gray>Активных скидок: <white>" + count + "</white></gray>",
                "<gray>Максимальная скидка: <white>" + plugin.getMarketConfig().discountMaxPercent() + "%</white></gray>",
                "",
                "<gray>Скидка применяется при покупке товаров</gray>",
                "<gray>с вашей витрины.</gray>"
        ));
    }

    private ItemStack formatEntry(DiscountEntry de) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        OfflinePlayer op = Bukkit.getOfflinePlayer(de.beneficiaryUuid());
        meta.setOwningPlayer(op);
        String name = op.getName() != null ? op.getName() : de.beneficiaryUuid().toString().substring(0, 8);
        meta.displayName(MessageUtils.parse(viewer, "<gold>" + name + "</gold>"));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<gray>Размер скидки: <green>" + de.percent() + "%</green></gray>"));
        String exp = de.expiresAt() <= 0 ? "бессрочно" : WHEN.format(Instant.ofEpochMilli(de.expiresAt()));
        lore.add(MessageUtils.parse(viewer, "<gray>Действует до: <white>" + exp + "</white></gray>"));
        lore.add(Component.empty());
        lore.add(MessageUtils.parse(viewer, "<red>ЛКМ </red><gray>— отменить скидку</gray>"));
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

        DiscountEntry de = entryAt.get(rawSlot);
        if (de != null) {
            try {
                plugin.getMarketRepository().removeDiscount(point.claimId(), de.beneficiaryUuid());
                OfflinePlayer op = Bukkit.getOfflinePlayer(de.beneficiaryUuid());
                viewer.sendMessage(MessageUtils.parse(viewer, "<green>Скидка игрока " + (op.getName() != null ? op.getName() : "") + " отменена.</green>"));
            } catch (Exception e) {
                viewer.sendMessage(MessageUtils.parse(viewer, "<red>Ошибка при отмене скидки.</red>"));
            }
            render();
        }
    }

    private void promptAdd() {
        viewer.closeInventory();
        int maxPercent = plugin.getMarketConfig().discountMaxPercent();
        promptPlayer("prompt-discount-target", this::open, target -> {
            promptNumber("prompt-discount-percent", 1, maxPercent, this::open, percent -> {
                promptNumber("prompt-discount-days", 0, 365, this::open, days -> {
                    Long expiresAt = days <= 0 ? null : System.currentTimeMillis() + (days * 86_400_000L);
                    try {
                        plugin.getMarketRepository().setDiscount(point.claimId(), target.getUniqueId(), (int) percent, expiresAt);
                        viewer.sendMessage(MessageUtils.parse(viewer, "<green>Скидка " + percent + "% выдана игроку " + target.getName() + "!</green>"));
                    } catch (Exception e) {
                        viewer.sendMessage(MessageUtils.parse(viewer, "<red>Ошибка при сохранении скидки.</red>"));
                    }
                    new StallDiscountGui(plugin, viewer, point).open();
                });
            });
        });
    }
}
