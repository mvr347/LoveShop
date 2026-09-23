package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.AuctionData;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class AuctionGui {

    public static final String TITLE = "Аукцион редких предметов";

    private final LoveShops plugin;
    private final Player player;

    public AuctionGui(LoveShops plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE).color(NamedTextColor.GOLD));

        // gui-gen-5 RULE 2/RULE 6: стекло — только в Header (0-8) и Footer (18-26).
        // Рабочая зона (9-17) стекла не получает вообще, даже под позициями без контента на
        // этот момент — раньше стекло сперва заливало и её тоже, и слоты без лота (когда
        // активных аукционов меньше 7) оставались стеклянными вместо пустых.
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i <= 8; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 18; i < 27; i++) {
            inv.setItem(i, filler);
        }

        // Slot 0: Player Profile Head
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));

        // Slot 26: Close button
        inv.setItem(26, GuiUtils.createCustomHead(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>", List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        // Content slots: 10-16 (7 slots)
        int[] slots = new int[]{10, 11, 12, 13, 14, 15, 16};

        plugin.getAuctionManager().getActiveAuctions().thenAccept(auctions -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                int idx = 0;
                long now = System.currentTimeMillis() / 1000;

                for (AuctionData auction : auctions) {
                    if (idx >= slots.length) break;

                    ItemStack baseItem = ItemStackConverter.itemStackFromBase64(auction.itemData());
                    if (baseItem == null) continue;

                    long remaining = Math.max(0, auction.endsAt() - now);
                    int minNextBid = plugin.getAuctionManager().getMinimumNextBid(auction);

                    ItemStack displayItem = baseItem.clone();
                    ItemMeta meta = displayItem.getItemMeta();
                    if (meta != null) {
                        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                        lore.add(Component.empty());
                        String icon = MessageUtils.currencyIcon();
                        lore.add(MessageUtils.parse(player, "<gray>Текущая ставка: <gold>" + icon + auction.currentHighestBid() + " монет</gold></gray>"));
                        if (auction.highestBidderUuid() != null) {
                            String leaderName = Bukkit.getOfflinePlayer(auction.highestBidderUuid()).getName();
                            lore.add(MessageUtils.parse("<gray>Лидирует: <aqua>" + (leaderName != null ? leaderName : "Неизвестный игрок") + "</aqua></gray>"));
                        } else {
                            lore.add(MessageUtils.parse("<gray>Ставок пока нет</gray>"));
                        }
                        lore.add(MessageUtils.parse(player, "<gray>Минимальный шаг: <yellow>" + icon + minNextBid + " монет</yellow></gray>"));
                        lore.add(MessageUtils.parse("<gray>До конца: <green>" + TimeUtils.formatRemainingTime(remaining) + "</green></gray>"));
                        if (auction.buyoutPrice() > 0) {
                            lore.add(MessageUtils.parse(player, "<gray>Выкуп: <light_purple>" + icon + auction.buyoutPrice() + " монет</light_purple></gray>"));
                        }
                        lore.add(MessageUtils.parse("<gray>ID Лота: <dark_gray>#" + auction.id() + "</dark_gray></gray>"));
                        lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— сделать ставку</gray>"));
                        if (auction.buyoutPrice() > 0) {
                            lore.add(MessageUtils.parse("<light_purple>Shift+ЛКМ </light_purple><gray>— выкупить сразу</gray>"));
                        }
                        meta.lore(lore);
                        displayItem.setItemMeta(meta);
                    }

                    inv.setItem(slots[idx], displayItem);
                    idx++;
                }

                player.openInventory(inv);
            });
        });
    }
}
