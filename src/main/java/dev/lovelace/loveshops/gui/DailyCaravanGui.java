package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.DailyCaravanManager;
import dev.lovelace.loveshops.models.caravan.DailyCrateAcceptedItem;
import dev.lovelace.loveshops.models.caravan.DailyCrateConfig;
import dev.lovelace.loveshops.models.caravan.DailyCrateState;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.ItemResolver;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.ProgressBarUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * GUI Ежедневного Караванщика (Daily Caravaner) по стандарту gui-gen-5:
 * - Размер 45 слотов
 * - Стекло только в Header (0-8) и Row 1 (9-17)
 * - Рабочая зона (18-35) без стекла, боковые слоты пустые
 * - Footer (36-44) со стеклом, кнопкой быстрой сдачи (40) и закрытием (44)
 */
public class DailyCaravanGui implements InventoryHolder {

    public static final String TITLE = "Караванщик";
    public static final String CRATE_ID_KEY = "caravan_crate_id";
    public static final int SLOT_SUBMIT_HAND = 40;
    public static final int SLOT_CLOSE = 44;

    private final LoveShops plugin;
    private final Player player;
    private final DailyCaravanManager manager;
    private Inventory inventory;

    public DailyCaravanGui(LoveShops plugin, Player player, DailyCaravanManager manager) {
        this.plugin = plugin;
        this.player = player;
        this.manager = manager;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 45, Component.text(TITLE).color(NamedTextColor.GOLD));
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

        long remainingSec = Math.max(0, manager.getVisitDespawnAt() - (System.currentTimeMillis() / 1000));
        long hours = remainingSec / 3600;
        long minutes = (remainingSec % 3600) / 60;
        String timeLeftStr = hours > 0 ? (hours + " ч. " + minutes + " мин.") : (minutes + " мин.");

        ItemStack infoItem = GuiUtils.createCustomHead(
                HeadTextures.BUTTON_BACK,
                "<gold>📦 Караванщик</gold>",
                List.of(
                        "",
                        "<gray>Сдавайте ресурсы каравану за монеты!</gray>",
                        "<gray>Осталось в городе: <yellow>" + timeLeftStr + "</yellow></gray>",
                        "<gray>Бонус за сдачу полными стаками: <green>+8-10%</green></gray>"
                )
        );
        inventory.setItem(0, infoItem);

        // 2. Row 1 Header (слоты 9-17) - по стандарту gui-gen-5 всегда 100% стекло
        for (int i = 9; i <= 17; i++) {
            inventory.setItem(i, filler);
        }

        // 3. Рабочая зона (слоты 18-35).
        // Стекло запрещено (RULE 6). Боковые слоты (18, 26, 27, 35) остаются AIR.
        List<DailyCrateState> crates = manager.getActiveCrates();
        int[] availableSlots = new int[]{20, 21, 22, 23, 24, 29, 30, 31, 32, 33};

        int slotIdx = 0;
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        for (DailyCrateState crate : crates) {
            if (slotIdx >= availableSlots.length) break;
            int targetSlot = availableSlots[slotIdx++];

            DailyCrateConfig cfg = manager.getCrateConfig(crate.crateKey());
            String iconId = cfg != null ? cfg.iconId() : "CHEST";
            ItemStack crateItem = ItemResolver.resolveItemStack(iconId, 1);
            ItemMeta meta = crateItem.getItemMeta();

            if (meta != null) {
                meta.displayName(MessageUtils.parse(crate.displayName()));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());

                // 1. Заполненность: полоска в скобках, подпись строкой ниже
                for (String line : ProgressBarUtil.formatStackProgressBar(crate.currentAmount(), crate.maxAmount(), 64, 20)) {
                    lore.add(MessageUtils.parse(line));
                }
                lore.add(Component.empty());

                // 2. Предметы (принимаются) — список на русском
                lore.add(MessageUtils.parse("<gray>Принимаются:</gray>"));
                if (cfg != null) {
                    for (DailyCrateAcceptedItem acc : cfg.acceptedItems()) {
                        String rusName = ItemResolver.getFriendlyRussianName(acc.itemId());
                        lore.add(MessageUtils.parse(" <dark_gray>•</dark_gray> <yellow>" + rusName + "</yellow>"));
                    }
                }
                lore.add(Component.empty());

                // 3. Цена
                if (crate.isUrgent()) {
                    long urgentSec = Math.max(0, crate.urgentExpiresAt() - (System.currentTimeMillis() / 1000));
                    long uMin = urgentSec / 60;
                    long uSec = urgentSec % 60;
                    lore.add(MessageUtils.parse("<red>⚡ СРОЧНЫЙ ЗАКАЗ!</red> <yellow>(" + uMin + ":" + (uSec < 10 ? "0" : "") + uSec + ")</yellow>"));
                }
                lore.add(MessageUtils.parse("<gray>Цена за шт: </gray>" + CoinFormat.formatGlyphs(eco, crate.pricePerUnit())));
                if (cfg != null && cfg.stackBonusPercent() > 0) {
                    lore.add(MessageUtils.parse("<gray>Бонус за стак: <green>+" + cfg.stackBonusPercent() + "% монет</green></gray>"));
                }
                lore.add(Component.empty());

                // 4. Действия/кнопки
                if (crate.isClosed()) {
                    lore.add(MessageUtils.parse("<red>[ ЯЩИК ЗАКРЫТ ]</red>"));
                } else {
                    lore.add(MessageUtils.parse("<yellow>ЛКМ </yellow><gray>— сдать предмет из руки</gray>"));
                    lore.add(MessageUtils.parse("<yellow>ПКМ </yellow><gray>— сдать все подходящие из инвентаря</gray>"));
                    lore.add(MessageUtils.parse("<yellow>Shift </yellow><gray>на предмете в инвентаре — сдать его</gray>"));
                }

                meta.lore(lore);
                meta.getPersistentDataContainer().set(dev.lovelace.loveshops.utils.Keys.of(plugin, CRATE_ID_KEY), PersistentDataType.INTEGER, crate.id());
                crateItem.setItemMeta(meta);
            }

            inventory.setItem(targetSlot, crateItem);
        }

        // 4. Footer (слоты 36-44)
        for (int i = 36; i <= 44; i++) {
            inventory.setItem(i, filler);
        }

        // Слот 44: Закрыть
        inventory.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню караванщика</gray>")
        ));
    }

    public static boolean isCrateSlot(int rawSlot) {
        return (rawSlot >= 20 && rawSlot <= 24) || (rawSlot >= 29 && rawSlot <= 33);
    }

    public static boolean handleCursorSubmit(LoveShops plugin, Player player, int rawSlot, ItemStack cursor) {
        if (cursor == null || cursor.getType().isAir()) return false;
        DailyCaravanManager manager = plugin.getDailyCaravanManager();
        if (manager == null || !manager.isCaravanerActive()) return false;

        Inventory top = player.getOpenInventory().getTopInventory();
        if (rawSlot >= 0 && rawSlot < top.getSize()) {
            ItemStack clicked = top.getItem(rawSlot);
            if (clicked != null && clicked.hasItemMeta()) {
                Integer crateId = clicked.getItemMeta().getPersistentDataContainer()
                        .get(dev.lovelace.loveshops.utils.Keys.of(plugin, CRATE_ID_KEY), PersistentDataType.INTEGER);
                if (crateId != null) {
                    DailyCaravanManager.SubmitResult result = manager.submitCursorItem(player, crateId, cursor);
                    if (result == DailyCaravanManager.SubmitResult.SUCCESS) {
                        return true;
                    }
                    switch (result) {
                        case NO_ITEMS -> MessageUtils.sendMessage(player, "<yellow>Этот предмет не подходит для данного ящика!</yellow>");
                        case CRATE_CLOSED -> MessageUtils.sendMessage(player, "<red>Этот ящик уже заполнен или закрыт.</red>");
                        case LIMIT_REACHED -> MessageUtils.sendMessage(player, "<red>Вы достигли лимита сдачи товаров за этот визит каравана!</red>");
                        case NO_SPACE -> MessageUtils.sendMessage(player, "<red>В вашем инвентаре нет места для монет выплаты!</red>");
                        default -> {}
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Shift-click of an inventory item: it goes into the first open crate that accepts it; whatever
     * is not taken (wrong item, full crate, limit) returns to the player.
     */
    public static void handleShiftSubmit(LoveShops plugin, Player player, ItemStack moved) {
        DailyCaravanManager manager = plugin.getDailyCaravanManager();
        if (manager != null && manager.isCaravanerActive()) {
            ItemStack held = moved.clone();
            DailyCaravanManager.SubmitResult last = DailyCaravanManager.SubmitResult.NO_ITEMS;
            for (DailyCrateState crate : new ArrayList<>(manager.getActiveCrates())) {
                if (held.getType().isAir()) break;
                player.setItemOnCursor(held);
                last = manager.submitCursorItem(player, crate.id(), held);
                ItemStack rest = player.getItemOnCursor();
                player.setItemOnCursor(null);
                held = rest == null ? new ItemStack(Material.AIR) : rest;
                if (last == DailyCaravanManager.SubmitResult.SUCCESS && held.getType().isAir()) break;
                if (last == DailyCaravanManager.SubmitResult.LIMIT_REACHED || last == DailyCaravanManager.SubmitResult.NO_SPACE) break;
            }
            if (last == DailyCaravanManager.SubmitResult.NO_ITEMS && !held.getType().isAir()) {
                MessageUtils.sendMessage(player, "<yellow>Этот предмет не подходит ни для одного ящика!</yellow>");
            } else if (last == DailyCaravanManager.SubmitResult.LIMIT_REACHED) {
                MessageUtils.sendMessage(player, "<red>Вы достигли лимита сдачи товаров за этот визит каравана!</red>");
            } else if (last == DailyCaravanManager.SubmitResult.NO_SPACE) {
                MessageUtils.sendMessage(player, "<red>В вашем инвентаре нет места для монет выплаты!</red>");
            }
            moved = held;
        }
        if (moved != null && !moved.getType().isAir()) {
            for (ItemStack drop : player.getInventory().addItem(moved).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
        }
        refreshAll(plugin);
    }

    public static void handleClick(LoveShops plugin, Player player, int rawSlot, ClickType clickType, Inventory openInv) {
        if (rawSlot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        DailyCaravanManager manager = plugin.getDailyCaravanManager();
        if (manager == null || !manager.isCaravanerActive()) {
            player.closeInventory();
            MessageUtils.sendMessage(player, "<red>Караванщик уже уехал!</red>");
            return;
        }

        ItemStack clicked = openInv.getItem(rawSlot);
        if (clicked != null && clicked.hasItemMeta()) {
            Integer crateId = clicked.getItemMeta().getPersistentDataContainer()
                    .get(dev.lovelace.loveshops.utils.Keys.of(plugin, CRATE_ID_KEY), PersistentDataType.INTEGER);
            if (crateId != null) {
                boolean allMatching = clickType.isRightClick() || clickType == ClickType.SHIFT_RIGHT;
                DailyCaravanManager.SubmitResult result = manager.submitItems(player, crateId, allMatching);

                switch (result) {
                    case NO_ITEMS -> MessageUtils.sendMessage(player, "<red>У вас нет подходящих предметов для этого ящика!</red>");
                    case CRATE_CLOSED -> MessageUtils.sendMessage(player, "<red>Этот ящик уже заполнен или закрыт.</red>");
                    case LIMIT_REACHED -> MessageUtils.sendMessage(player, "<red>Вы достигли лимита сдачи товаров за этот визит каравана!</red>");
                    case NO_SPACE -> MessageUtils.sendMessage(player, "<red>В вашем инвентаре нет места для монет выплаты!</red>");
                    default -> {}
                }

                refreshAll(plugin);
            }
        }
    }

    public static void refresh(LoveShops plugin, Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof DailyCaravanGui gui) {
            gui.render();
        }
    }

    public static void refreshAll(LoveShops plugin) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof DailyCaravanGui gui) {
                gui.render();
            }
        }
    }
}
