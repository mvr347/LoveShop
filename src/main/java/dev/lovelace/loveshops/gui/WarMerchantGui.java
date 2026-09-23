package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.WarMerchantManager;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Военный торговец - фиксированный ассортимент из {@code war-merchant.items} в config.yml,
 * доступный только игрокам, прошедшим {@link WarMerchantManager#isEligible}. Сама проверка
 * права доступа выполняется до open() (см. CitizensListener/InventoryClickListener), это меню
 * просто рендерит товар.
 */
public class WarMerchantGui {

    public static final String TITLE = "Военный торговец";

    private final LoveShops plugin;
    private final Player player;

    public WarMerchantGui(LoveShops plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE).color(NamedTextColor.DARK_RED));

        // gui-gen-5: боковые стенки рабочей зоны (9, 17) всегда пустые - стекла в рабочей
        // зоне не бывает никогда, даже на позициях без контента (RULE 6), см. BuyerGui.
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i < 27; i++) {
            if (i == 9 || i == 17) continue;
            inv.setItem(i, filler);
        }

        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));
        inv.setItem(26, GuiUtils.createCustomHead(HeadTextures.BUTTON_CLOSE, "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        int[] contentSlots = new int[]{10, 11, 12, 13, 14, 15, 16};
        List<WarMerchantManager.MerchantItem> items = plugin.getWarMerchantManager().getItems();

        int slotIdx = 0;
        for (WarMerchantManager.MerchantItem merchantItem : items) {
            if (slotIdx >= contentSlots.length) break;

            ItemStack displayItem = merchantItem.display().clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<gray>Цена: <gold>" + merchantItem.price() + " "
                        + plugin.getEconomy().map(e -> e.currencyName()).orElse("монет") + "</gold></gray>"));
                lore.add(MessageUtils.parse("<green>ЛКМ </green><gray>— купить товар</gray>"));
                meta.lore(lore);
                // Индекс в каноническом списке config.yml - клик-обработчик пересчитывает
                // цену/товар по нему заново, а не доверяет лору отображаемого предмета.
                meta.getPersistentDataContainer().set(
                        new NamespacedKey(plugin, "war_merchant_index"), PersistentDataType.INTEGER, slotIdx);
                displayItem.setItemMeta(meta);
            }

            inv.setItem(contentSlots[slotIdx], displayItem);
            slotIdx++;
        }

        player.openInventory(inv);
    }
}
