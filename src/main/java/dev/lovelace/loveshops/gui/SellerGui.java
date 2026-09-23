package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.PriceCalculator;
import dev.lovelace.loveshops.models.BuyerItemData;
import dev.lovelace.loveshops.textures.HeadTextures;
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
import java.util.Map;

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

        // gui-gen-5: боковые стенки рабочей зоны (18, 26, 27, 35, 36, 44) всегда пустые —
        // стекла в рабочей зоне не бывает никогда, даже на позициях без контента (RULE 6).
        ItemStack filler = GuiUtils.createFiller();
        java.util.Set<Integer> workingZoneWalls = java.util.Set.of(18, 26, 27, 35, 36, 44);
        for (int i = 0; i < 54; i++) {
            if (workingZoneWalls.contains(i)) continue;
            inv.setItem(i, filler);
        }

        // Slot 0: Player Profile Head
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));

        // Slot 53: Close button ALWAYS in slot 53 (Golden Rule 6)
        inv.setItem(53, GuiUtils.createCustomHead(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>", List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        // Working Area content slots (Golden Rule 5):
        // Row 2: 19-25, Row 3: 28-34, Row 4: 37-43 (21 slots total)
        int[] contentSlots = new int[]{
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
        };

        plugin.getSellerManager().getAvailableItems().thenAccept(items ->
            plugin.getPriceCalculator().calculateSellPrices(items).thenAccept(prices ->
                Bukkit.getScheduler().runTask(plugin, () -> renderItems(inv, items, prices, contentSlots))
            )
        );
    }

    private void renderItems(Inventory inv, List<BuyerItemData> items, Map<Integer, Integer> prices, int[] contentSlots) {
        int idx = 0;
        for (BuyerItemData itemData : items) {
            if (idx >= contentSlots.length) break;

            ItemStack baseItem = ItemStackConverter.itemStackFromBase64(itemData.itemData());
            if (baseItem == null) continue;

            int price = prices.getOrDefault(itemData.id(), itemData.basePrice());
            ItemStack displayItem = baseItem.clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<gray>Цена: <gold>" + price + " " + plugin.getEconomy().map(e -> e.currencyName()).orElse("монет") + "</gold></gray>"));
                lore.add(MessageUtils.parse(trendLine(itemData.basePrice(), price)));
                lore.add(MessageUtils.parse("<gray>ID Лота: <dark_gray>#" + itemData.id() + "</dark_gray></gray>"));
                lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— купить товар</gray>"));
                meta.lore(lore);
                displayItem.setItemMeta(meta);
            }

            inv.setItem(contentSlots[idx], displayItem);
            idx++;
        }

        player.openInventory(inv);
    }

    /**
     * Строка тренда для лота. Спрос, предложение и шум цикла уже влияли на цену,
     * но игрок видел только итоговое число и не понимал, дорого сейчас или дёшево.
     */
    private String trendLine(int basePrice, int price) {
        PriceCalculator.PriceTrend trend = plugin.getPriceCalculator().getTrend(basePrice, price);
        return switch (trend) {
            case RISING -> "<gray>Спрос: <red>▲ цена растёт</red></gray>";
            case FALLING -> "<gray>Спрос: <green>▼ цена падает</green></gray>";
            case STABLE -> "<gray>Спрос: <yellow>— цена спокойна</yellow></gray>";
        };
    }
}
