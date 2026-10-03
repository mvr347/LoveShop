package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.LostCaravanManager;
import dev.lovelace.loveshops.models.caravan.LostCaravanLot;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI Мгновенной покупки ящиков Потерянного Каравана (36 слотов):
 * - Размер 36 слотов по стандарту gui-gen-5
 * - Header (0-8): профиль игрока (0), инфо (4), стекло (1-3, 5-8)
 * - Рабочая зона (9-26): без стекла! Доступные для выкупа ящики
 * - Footer (27-35): стекло, слот 35 — «Закрыть»
 */
public class LostCaravanInstantGui implements InventoryHolder {

    public static final String TITLE = "Караван: Покупка";
    public static final String LOT_INDEX_KEY = "caravan_instant_lot_idx";
    public static final int SLOT_CLOSE = 35;

    private static final int[] LOT_SLOTS = new int[]{20, 21, 22, 23, 24, 25};

    private final LoveShops plugin;
    private final Player player;
    private final LostCaravanManager manager;
    private Inventory inventory;

    public LostCaravanInstantGui(LoveShops plugin, Player player, LostCaravanManager manager) {
        this.plugin = plugin;
        this.player = player;
        this.manager = manager;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 36, Component.text(TITLE).color(NamedTextColor.GOLD));
        render();
        player.openInventory(inventory);
    }

    public void render() {
        inventory.clear();
        ItemStack filler = GuiUtils.createFiller();

        // 1. Header (слоты 0-8)
        for (int i = 0; i <= 8; i++) {
            inventory.setItem(i, filler);
        }

        ItemStack infoItem = GuiUtils.createCustomHead(
                HeadTextures.BUTTON_BACK,
                "<gold>📦 Мгновенная покупка ящиков</gold>",
                List.of(
                        "",
                        "<gray>В караване мало участников — режим аукциона отключён!</gray>",
                        "<gray>Вы можете выкупить ящики по фиксированной цене.</gray>",
                        "<gray>Первый купивший забирает ящик!</gray>"
                )
        );
        inventory.setItem(0, infoItem);

        // 2. Рабочая зона (слоты 9-26) - без стекла
        List<LostCaravanLot> lots = manager.getActiveLots();
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        for (int i = 0; i < lots.size() && i < LOT_SLOTS.length; i++) {
            LostCaravanLot lot = lots.get(i);
            int slot = LOT_SLOTS[i];

            ItemStack displayItem;
            if ("SOLD".equalsIgnoreCase(lot.status())) {
                displayItem = GuiUtils.createCustomHead(
                        HeadTextures.HEAD_DELETE_NO,
                        "<red>[ РАСПРОДАНО ]</red>",
                        List.of("", "<gray>Этот ящик уже приобрёл другой игрок.</gray>")
                );
            } else {
                displayItem = lot.crateItem().clone();
                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<Component> lore = new ArrayList<>();
                    lore.add(Component.empty());
                    if (lot.secret()) {
                        lore.add(MessageUtils.parse("<red>⚡ СЕКРЕТНЫЙ ЯЩИК</red>"));
                    } else {
                        lore.add(MessageUtils.parse("<gold>Ящик #" + (i + 1) + "</gold>"));
                    }
                    lore.add(Component.empty());
                    lore.add(MessageUtils.parse("<gray>Цена: </gray>" + CoinFormat.formatGlyphs(eco, lot.startingPrice())));
                    lore.add(Component.empty());
                    lore.add(MessageUtils.parse("<green>ЛКМ — купить ящик</green>"));
                    meta.lore(lore);
                    meta.getPersistentDataContainer().set(new NamespacedKey(plugin, LOT_INDEX_KEY), PersistentDataType.INTEGER, i);
                    displayItem.setItemMeta(meta);
                }
            }
            inventory.setItem(slot, displayItem);
        }

        // 3. Footer (слоты 27-35)
        for (int i = 27; i <= 35; i++) {
            inventory.setItem(i, filler);
        }
        inventory.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню</gray>")
        ));
    }

    public static void handleClick(LoveShops plugin, Player player, int rawSlot, ClickType clickType, Inventory openInv) {
        if (!(openInv.getHolder() instanceof LostCaravanInstantGui gui)) return;

        if (rawSlot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        ItemStack clicked = openInv.getItem(rawSlot);
        if (clicked != null && clicked.hasItemMeta()) {
            Integer lotIdx = clicked.getItemMeta().getPersistentDataContainer()
                    .get(new NamespacedKey(plugin, LOT_INDEX_KEY), PersistentDataType.INTEGER);
            if (lotIdx != null) {
                LostCaravanManager manager = plugin.getLostCaravanManager();
                if (manager != null) {
                    manager.buyInstantCrate(player, lotIdx);
                    refreshAll(plugin);
                }
            }
        }
    }

    public static void refresh(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof LostCaravanInstantGui gui) {
            gui.render();
        }
    }

    public static void refreshAll(LoveShops plugin) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof LostCaravanInstantGui gui) {
                gui.render();
            }
        }
    }
}
