package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.WandererDeal;
import dev.lovelace.loveshops.models.WandererDealItem;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import dev.lovelace.loveshops.textures.HeadTextures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WandererShopGui {

    public static final String TITLE = "Странник (Контрабанда)";
    public static final String INFO_HEAD_BASE64 = HeadTextures.WANDERER_INFO;
    public static final String RESET_HEAD_BASE64 = HeadTextures.WANDERER_RESET;

    // Content slots for 54-slot menu (Golden Rule 5)
    public static final int[] CONTENT_SLOTS = new int[]{
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private final LoveShops plugin;
    private final Player player;
    private final WandererDeal deal;
    private final Map<Integer, String> slotToDealItemId = new HashMap<>();

    public WandererShopGui(LoveShops plugin, Player player, WandererDeal deal) {
        this.plugin = plugin;
        this.player = player;
        this.deal = deal;
    }

    public void open() {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(TITLE).color(NamedTextColor.DARK_PURPLE));

        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < 54; i++) {
            inv.setItem(i, filler);
        }

        // Slot 0: Player Profile Head
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));

        // Slot 4: Info Head
        long expireRemaining = deal != null && deal.expiresAt() > 0 ?
            Math.max(0, deal.expiresAt() - (System.currentTimeMillis() / 1000)) : 0;
        String expireStr = TimeUtils.formatRemainingTime(expireRemaining);

        inv.setItem(4, GuiUtils.createCustomHead(INFO_HEAD_BASE64, "<light_purple><bold>Заказ Странника</bold></light_purple>",
            List.of(
                "",
                "<gray>Товары, доставленные специально для вас.</gray>",
                "<gray>Срок действия предложения: <gold>" + expireStr + "</gold></gray>",
                "",
                "<dark_purple>Доступно только бойцам с агрессивным стилем!</dark_purple>"
            )));

        // Slot 51: Reset / Complete Deal button
        inv.setItem(51, GuiUtils.createCustomHead(RESET_HEAD_BASE64, "<gold><bold>Завершить заказ</bold></gold>",
            List.of(
                "",
                "<gray>Завершает текущую партию товаров,</gray>",
                "<gray>позволяя заключить новый договор.</gray>",
                "",
                "<yellow>ЛКМ </yellow><gray>— завершить заказ</gray>"
            )));

        // Slot 53: Close button ALWAYS
        inv.setItem(53, GuiUtils.createCustomHead(GuiUtils.BTN_CLOSE_BASE64, "<red>Закрыть</red>",
            List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        // Populate content slots
        if (deal != null && deal.items() != null) {
            List<WandererDealItem> items = deal.items();
            for (int i = 0; i < items.size() && i < CONTENT_SLOTS.length; i++) {
                int slot = CONTENT_SLOTS[i];
                WandererDealItem item = items.get(i);
                slotToDealItemId.put(slot, item.id());

                if (item.bought()) {
                    ItemStack soldItem = new ItemStack(Material.GRAY_DYE);
                    ItemMeta meta = soldItem.getItemMeta();
                    if (meta != null) {
                        meta.displayName(MessageUtils.parse("<dark_gray><st>" + item.name() + "</st></dark_gray>"));
                        meta.lore(List.of(
                            Component.empty(),
                            MessageUtils.parse("<green>✓ Товар уже приобретён!</green>")
                        ));
                        soldItem.setItemMeta(meta);
                    }
                    inv.setItem(slot, soldItem);
                } else {
                    ItemStack base = ItemStackConverter.itemStackFromBase64(item.itemDataBase64());
                    if (base != null) {
                        ItemStack display = base.clone();
                        ItemMeta meta = display.getItemMeta();
                        if (meta != null) {
                            List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                            lore.add(Component.empty());
                            String currency = plugin.getEconomy().map(LoveEconomy::currencyName).orElse("монет");
                            lore.add(MessageUtils.parse("<gray>Цена: <gold>" + item.price() + " " + currency + "</gold></gray>"));
                            lore.add(MessageUtils.parse("<green><bold>ЛКМ</bold> </green><gray>— приобрести предмет</gray>"));
                            meta.lore(lore);
                            display.setItemMeta(meta);
                        }
                        inv.setItem(slot, display);
                    }
                }
            }
        }

        player.openInventory(inv);
    }

    public String getDealItemIdBySlot(int slot) {
        return slotToDealItemId.get(slot);
    }
}
