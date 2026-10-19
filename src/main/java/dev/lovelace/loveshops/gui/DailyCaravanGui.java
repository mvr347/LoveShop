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
import dev.lovelace.loveshops.utils.LoreBlocks;
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

    private dev.lovelace.loveshops.managers.LangManager lang() {
        return plugin.getLangManager();
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
                lang().text("gui.daily.info"),
                lang().lines("gui.daily.info-lore", "time", timeLeftStr)
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
            String texture;
            if (crate.isClosed()) {
                texture = HeadTextures.CARAVAN_CRATE_COMPLETED;
            } else if (cfg != null && cfg.iconId() != null && cfg.iconId().startsWith("eyJ")) {
                texture = cfg.iconId();
            } else {
                texture = HeadTextures.CARAVAN_CRATE_NORMAL;
            }
            ItemStack crateItem = GuiUtils.createCustomHead(texture, crate.displayName(), List.of());
            ItemMeta meta = crateItem.getItemMeta();

            if (meta != null) {
                // Name | state: the configured crate name plus its current state (gui-gen-6)
                String state = crate.isClosed() ? "gui.daily.state-closed" : crate.isUrgent() ? "gui.daily.state-urgent" : "gui.daily.state-open";
                meta.displayName(GuiUtils.line(player, crate.displayName() + lang().text(state)));

                List<String> loreLines = new ArrayList<>();
                loreLines.add(lang().text("gui.daily.crate-subtitle"));
                loreLines.add("");

                // 1. Заполненность: 16-клеточная шкала с процентом
                long stackSize = 64;
                long curStacks = crate.currentAmount() / stackSize;
                long maxStacks = Math.max(1, crate.maxAmount() / stackSize);
                loreLines.add(lang().text("gui.daily.fill-section"));
                loreLines.add("  " + ProgressBarUtil.bar(curStacks, maxStacks));
                loreLines.add(lang().text("gui.daily.fill-count", "current", String.valueOf(curStacks), "max", String.valueOf(maxStacks)));
                loreLines.add("");

                // 2. Предметы (принимаются) — список на русском
                loreLines.add(lang().text("gui.daily.accepts-section"));
                if (cfg != null) {
                    for (DailyCrateAcceptedItem acc : cfg.acceptedItems()) {
                        loreLines.add(lang().text("gui.daily.accepts-row", "item", ItemResolver.getFriendlyRussianName(acc.itemId())));
                    }
                }
                loreLines.add("");

                // 3. Цена
                loreLines.add(lang().text("gui.daily.price-section"));
                if (crate.isUrgent()) {
                    long urgentSec = Math.max(0, crate.urgentExpiresAt() - (System.currentTimeMillis() / 1000));
                    long uMin = urgentSec / 60;
                    long uSec = urgentSec % 60;
                    loreLines.add(lang().text("gui.daily.urgent-row", "time", uMin + ":" + (uSec < 10 ? "0" : "") + uSec));
                }
                loreLines.add(lang().text("gui.daily.price-row", "price", CoinFormat.formatGlyphs(eco, crate.pricePerUnit())));
                if (cfg != null && cfg.stackBonusPercent() > 0) {
                    loreLines.add(lang().text("gui.daily.bonus-row", "percent", String.valueOf(cfg.stackBonusPercent())));
                }

                // 4. Действия/кнопки
                if (crate.isClosed()) {
                    loreLines.addAll(LoreBlocks.blocked(lang().style(), "unavailable", lang().text("gui.daily.closed-reason"),
                            LoreBlocks.hint(LoreBlocks.Click.LEFT, lang().text("gui.daily.click-hand"))));
                } else {
                    loreLines.addAll(LoreBlocks.clicks(lang().style(),
                            LoreBlocks.hint(LoreBlocks.Click.LEFT, lang().text("gui.daily.click-hand")),
                            LoreBlocks.hint(LoreBlocks.Click.RIGHT, lang().text("gui.daily.click-all")),
                            LoreBlocks.hint(LoreBlocks.Click.SHIFT_LEFT, lang().text("gui.daily.click-stack"))));
                }

                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) lore.add(GuiUtils.line(player, line));
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
                lang().text("gui.close"),
                lang().lines("gui.close-lore")
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
                        case NO_ITEMS -> MessageUtils.event(player, "<yellow>Этот предмет не подходит для данного ящика!</yellow>");
                        case CRATE_CLOSED -> MessageUtils.event(player, "<red>Этот ящик уже заполнен или закрыт.</red>");
                        case LIMIT_REACHED -> MessageUtils.event(player, "<red>Вы достигли лимита сдачи товаров за этот визит каравана!</red>");
                        case NO_SPACE -> MessageUtils.event(player, "<red>В вашем инвентаре нет места для монет выплаты!</red>");
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
                MessageUtils.event(player, "<yellow>Этот предмет не подходит ни для одного ящика!</yellow>");
            } else if (last == DailyCaravanManager.SubmitResult.LIMIT_REACHED) {
                MessageUtils.event(player, "<red>Вы достигли лимита сдачи товаров за этот визит каравана!</red>");
            } else if (last == DailyCaravanManager.SubmitResult.NO_SPACE) {
                MessageUtils.event(player, "<red>В вашем инвентаре нет места для монет выплаты!</red>");
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
            MessageUtils.event(player, "<red>Караванщик уже уехал!</red>");
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
                    case NO_ITEMS -> MessageUtils.event(player, "<red>У вас нет подходящих предметов для этого ящика!</red>");
                    case CRATE_CLOSED -> MessageUtils.event(player, "<red>Этот ящик уже заполнен или закрыт.</red>");
                    case LIMIT_REACHED -> MessageUtils.event(player, "<red>Вы достигли лимита сдачи товаров за этот визит каравана!</red>");
                    case NO_SPACE -> MessageUtils.event(player, "<red>В вашем инвентаре нет места для монет выплаты!</red>");
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
