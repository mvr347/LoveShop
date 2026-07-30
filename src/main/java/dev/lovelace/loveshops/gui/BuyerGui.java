package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.PriceCalculator;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BuyerGui {

    public static final String TITLE = "Скупщик предметов";

    private final LoveShops plugin;
    private final Player player;

    public BuyerGui(LoveShops plugin, Player player) {
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
        inv.setItem(26, GuiUtils.createCustomHead(GuiUtils.BTN_CLOSE_BASE64, "<red>Закрыть</red>", List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        // Content slots: 10-16 (7 slots)
        int[] contentSlots = new int[]{10, 11, 12, 13, 14, 15, 16};
        int slotIdx = 0;

        // Штраф за повторную сдачу копится в базе, но игроку никогда не показывался:
        // цена просто падала от захода к заходу. Берём всю историю одним запросом.
        Map<String, PriceCalculator.SubmissionHistory> history =
            plugin.getPriceCalculator().getSubmissionHistory(player.getUniqueId());

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (plugin.getEconomy().map(e -> e.isCoin(item)).orElse(false)) continue;
            if (slotIdx >= contentSlots.length) break;

            int price = plugin.getPriceCalculator().calculateBuyPrice(player, item);
            ItemStack displayItem = item.clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<gray>Цена скупки: <gold>" + price + " " + plugin.getEconomy().map(e -> e.currencyName()).orElse("монет") + "</gold></gray>"));

                PriceCalculator.SubmissionHistory submitted = history.get(item.getType().name());
                if (submitted != null && submitted.penaltyPercent() > 0) {
                    lore.add(MessageUtils.parse("<red>Вы сдавали это " + submitted.submitCount()
                        + " раз — цена снижена на " + Math.round(submitted.penaltyPercent()) + "%</red>"));
                }

                lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— продать скупщику</gray>"));
                meta.lore(lore);
                displayItem.setItemMeta(meta);
            }

            inv.setItem(contentSlots[slotIdx], displayItem);
            slotIdx++;
        }

        player.openInventory(inv);
    }
}
