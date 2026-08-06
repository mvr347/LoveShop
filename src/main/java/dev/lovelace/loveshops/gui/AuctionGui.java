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

        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < 27; i++) {
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
                        lore.add(MessageUtils.parse("<gray>Текущая ставка: <gold>" + auction.currentHighestBid() + " монет</gold></gray>"));
                        lore.add(MessageUtils.parse("<gray>Минимальный шаг: <yellow>" + minNextBid + " монет</yellow></gray>"));
                        lore.add(MessageUtils.parse("<gray>До конца: <green>" + TimeUtils.formatRemainingTime(remaining) + "</green></gray>"));
                        if (auction.buyoutPrice() > 0) {
                            lore.add(MessageUtils.parse("<gray>Выкуп: <light_purple>" + auction.buyoutPrice() + " монет</light_purple></gray>"));
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
