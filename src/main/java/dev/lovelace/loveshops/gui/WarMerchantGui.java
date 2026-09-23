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
 * Военный торговец ("Странник" в игровом обиходе — owner 2026-09-23: внутреннее имя
 * war-merchant/"Военный торговец" остаётся в коде, конфиг-ключах и типе NPC без изменений
 * (не breaking change для уже настроенных серверов и уже созданных /loveshopsadmin npc create
 * warmerchant НПС), меняется только то, что видит игрок - заголовок меню и реплики) -
 * фиксированный ассортимент из {@code war-merchant.items} в config.yml (с 2026-09-23,
 * опционально, ротацией подмножества - см. {@link WarMerchantManager#getRotatedIndices()}),
 * доступный только игрокам, прошедшим {@link WarMerchantManager#isEligible}. Сама проверка
 * права доступа выполняется до open() (см. CitizensListener/InventoryClickListener), это меню
 * просто рендерит товар.
 */
public class WarMerchantGui {

    public static final String TITLE = "Странник";

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
        // Ротация (war-merchant.rotation, добавлено 2026-09-23) - выбирает подмножество
        // канонического списка на текущий период, чтобы ассортимент ощущался как меняющиеся
        // "тёмные" предложения, а не статичная лавка. Индексы ниже - позиции в items выше
        // (getItems()), а не позиции на экране - purchase(index) продолжает работать с тем же
        // каноническим списком независимо от того, что сейчас показано в ротации.
        List<Integer> rotatedIndices = plugin.getWarMerchantManager().getRotatedIndices();

        int slotIdx = 0;
        for (int canonicalIndex : rotatedIndices) {
            if (slotIdx >= contentSlots.length) break;
            WarMerchantManager.MerchantItem merchantItem = items.get(canonicalIndex);

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
                        new NamespacedKey(plugin, "war_merchant_index"), PersistentDataType.INTEGER, canonicalIndex);
                displayItem.setItemMeta(meta);
            }

            inv.setItem(contentSlots[slotIdx], displayItem);
            slotIdx++;
        }

        player.openInventory(inv);
    }
}
