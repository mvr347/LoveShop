package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.WandererRequestCategory;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

import dev.lovelace.loveshops.textures.HeadTextures;

public class WandererDealGui {

    public static final String TITLE = "Странник (Договор)";
    public static final String CONTRACT_HEAD_BASE64 = HeadTextures.WANDERER_CONTRACT;

    // Content-row slots (9-17) used for the standard deal button + the single paid personal-
    // request button — see InventoryClickListener's WandererDealGui branch.
    public static final int SLOT_STANDARD_DEAL = 13;
    // 2026-09-24: was 4 separate buttons (one per category, slots 10/11/12/14) — collapsed into
    // ONE button that cycles through WandererRequestCategory on ЛКМ and confirms the currently
    // shown category on ПКМ (see buildCategoryButton()/InventoryClickListener). The selected
    // category's ordinal is stamped onto the item via CATEGORY_INDEX_KEY so the click handler
    // doesn't need any per-player state to know what's currently shown.
    public static final int SLOT_CATEGORY_REQUEST = 11;
    public static final String CATEGORY_INDEX_KEY = "wanderer_category_index";

    private final LoveShops plugin;
    private final Player player;

    public WandererDealGui(LoveShops plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    public void open() {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(TITLE).color(NamedTextColor.DARK_PURPLE));

        // gui-gen-5 RULE 2/RULE 6: стекло — только в Header (0-8) и Footer (18-26). Рабочая
        // зона (9-17) стекла не получает — единственный реальный контент здесь (кнопка сделки
        // в слоте 13), остальные слоты рабочей зоны остаются пустыми, а не стеклянными.
        ItemStack filler = GuiUtils.createFiller();
        for (int i = 0; i <= 8; i++) {
            inv.setItem(i, filler);
        }
        for (int i = 18; i < 27; i++) {
            inv.setItem(i, filler);
        }

        // Slot 0: Player Profile Head (Golden Rule 2)
        inv.setItem(0, GuiUtils.createPlayerProfileHead(player));

        // Slot 26: Close button ALWAYS in corner for 27-slot menu
        inv.setItem(26, GuiUtils.createCustomHead(GuiUtils.BTN_CLOSE_BASE64, "<red>Закрыть</red>",
            List.of("", "<gray>Выход из меню</gray>", "<red>ЛКМ </red><gray>— закрыть</gray>")));

        int cost = plugin.getConfig().getInt("wanderer.deal.cost", 150);
        int deliveryMinutes = plugin.getConfig().getInt("wanderer.deal.delivery-time-minutes", 60);
        String currencyName = plugin.getEconomy().map(LoveEconomy::currencyName).orElse("монет");
        String icon = MessageUtils.currencyIcon();
        String timeStr = TimeUtils.formatRemainingTime(deliveryMinutes * 60L);

        // Slot 13: Standard (unfiltered) deal button
        ItemStack dealBtn = GuiUtils.createCustomHead(CONTRACT_HEAD_BASE64, "<gold><bold>Заключить сделку со Странником</bold></gold>",
            List.of(
                "",
                "<gray>Странник отправится в запретные земли и</gray>",
                "<gray>добудет случайный набор редкой контрабанды.</gray>",
                "",
                "<gray>Стоимость аванса: <gold>" + icon + cost + " " + currencyName + "</gold></gray>",
                "<gray>Срок доставки: <gold>" + timeStr + "</gold></gray>",
                "",
                "<green><bold>ЛКМ</bold> </green><gray>— договориться и отправить Странника</gray>"
            ));
        inv.setItem(SLOT_STANDARD_DEAL, dealBtn);

        // Slot 11: single cycling paid personal-request button — ЛКМ cycles which category is
        // currently shown, ПКМ confirms and orders that category (see InventoryClickListener).
        boolean personalRequestEnabled = plugin.getConfig().getBoolean("wanderer.deal.personal-request.enabled", true);
        if (personalRequestEnabled) {
            inv.setItem(SLOT_CATEGORY_REQUEST, buildCategoryButton(plugin, WandererRequestCategory.values()[0]));
        }

        player.openInventory(inv);
    }

    /**
     * Renders the single cycling category-request button for the given currently-selected
     * category. Static and reusable so InventoryClickListener can rebuild the exact same item
     * in place after a ЛКМ cycle, without a full GUI reopen. The selected category's ordinal is
     * stamped into {@link #CATEGORY_INDEX_KEY} so the listener can read "what's shown right now"
     * straight off the clicked ItemStack instead of tracking per-player GUI state elsewhere.
     */
    public static ItemStack buildCategoryButton(LoveShops plugin, WandererRequestCategory category) {
        int baseCost = plugin.getConfig().getInt("wanderer.deal.cost", 150);
        double surchargePercent = plugin.getConfig().getDouble("wanderer.deal.personal-request.surcharge-percent", 50);
        int personalCost = (int) Math.round(baseCost * (1 + surchargePercent / 100.0));
        int deliveryMinutes = plugin.getConfig().getInt("wanderer.deal.delivery-time-minutes", 60);
        String currencyName = plugin.getEconomy().map(LoveEconomy::currencyName).orElse("монет");
        String icon = MessageUtils.currencyIcon();
        String timeStr = TimeUtils.formatRemainingTime(deliveryMinutes * 60L);

        ItemStack item = new ItemStack(materialFor(category));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MessageUtils.parse("<light_purple><bold>Заказ: " + category.displayName() + "</bold></light_purple>"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>Странник принесёт партию ТОЛЬКО из</gray>"));
            lore.add(MessageUtils.parse("<gray>выбранной категории — с доплатой.</gray>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<gray>Текущий выбор: <light_purple><bold>" + category.displayName() + "</bold></light_purple></gray>"));
            lore.add(MessageUtils.parse("<gray>Стоимость заказа: <gold>" + icon + personalCost + " " + currencyName + "</gold></gray>"));
            lore.add(MessageUtils.parse("<gray>Срок доставки: <gold>" + timeStr + "</gold></gray>"));
            lore.add(Component.empty());
            lore.add(MessageUtils.parse("<yellow><bold>ЛКМ</bold> </yellow><gray>— сменить категорию</gray>"));
            lore.add(MessageUtils.parse("<green><bold>ПКМ</bold> </green><gray>— заказать текущую категорию</gray>"));
            meta.lore(lore);
            meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, CATEGORY_INDEX_KEY), PersistentDataType.INTEGER, category.ordinal());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Material materialFor(WandererRequestCategory category) {
        return switch (category) {
            case TOOLS -> Material.NETHERITE_PICKAXE;
            case ARMOR -> Material.NETHERITE_CHESTPLATE;
            case ENCHANTMENTS -> Material.ENCHANTED_BOOK;
            case RARE -> Material.NETHER_STAR;
        };
    }
}
