package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.BuyerItemData;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class SellerGui {

    public static final String TITLE = "Барахолка (Товары недели)";

    private final LoveShops plugin;
    private final Player player;

    public SellerGui(LoveShops plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(TITLE).color(NamedTextColor.GOLD));

        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }

        // Slot 0: Player Profile Head
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));

        // Slots 2-7: Control tabs
        inv.setItem(2, GuiUtils.createCustomHead(GuiUtils.TAB_BUYER_BASE64, "<gold>Скупщик</gold>", List.of("", "<gray>Раздел сдачи предметов</gray>", "<green>ЛКМ </green><gray>— перейти</gray>")));
        inv.setItem(3, GuiUtils.createCustomHead(GuiUtils.TAB_SELLER_BASE64, "<green>Барахолка</green>", List.of("", "<gray>Товары недели</gray>", "<green>● Активно</green>")));
        inv.setItem(4, GuiUtils.createCustomHead(GuiUtils.TAB_AUCTION_BASE64, "<gold>Аукцион</gold>", List.of("", "<gray>Редкие лоты</gray>", "<green>ЛКМ </green><gray>— перейти</gray>")));

        // Slot 53: Close button ALWAYS in slot 53 (Golden Rule 6)
        inv.setItem(53, GuiUtils.createCustomHead(GuiUtils.BTN_CLOSE_BASE64, "<red>Закрыть</red>", List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        // Working Area content slots (Golden Rule 5):
        // Row 2: 19-25, Row 3: 28-34, Row 4: 37-43 (21 slots total)
        int[] contentSlots = new int[]{
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        };

        plugin.getSellerManager().getAvailableItems().thenAccept(items -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                double markup = plugin.getConfig().getDouble("seller.markup-percent", 15.0);

                int idx = 0;
                for (BuyerItemData itemData : items) {
                    if (idx >= contentSlots.length) break;

                    ItemStack baseItem = ItemStackConverter.itemStackFromBase64(itemData.itemData());
                    if (baseItem == null) continue;

                    int price = (int) Math.round(itemData.basePrice() * (1.0 + markup / 100.0));
                    ItemStack displayItem = baseItem.clone();
                    ItemMeta meta = displayItem.getItemMeta();
                    if (meta != null) {
                        List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                        lore.add(Component.empty());
                        lore.add(MessageUtils.parse("<gray>Цена: <gold>" + price + " " + plugin.getCurrencyManager().getCurrencyName() + "</gold></gray>"));
                        lore.add(MessageUtils.parse("<gray>ID Лота: <dark_gray>#" + itemData.id() + "</dark_gray></gray>"));
                        lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— купить товар</gray>"));
                        meta.lore(lore);
                        displayItem.setItemMeta(meta);
                    }

                    inv.setItem(contentSlots[idx], displayItem);
                    idx++;
                }

                player.openInventory(inv);
            });
        });
    }
}
