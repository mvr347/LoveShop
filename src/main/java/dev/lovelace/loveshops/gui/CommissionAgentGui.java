package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.CommissionManager;
import dev.lovelace.loveshops.models.commission.CommissionLot;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GUI Комиссионера (Commission Agent) по стандарту gui-gen-5:
 * - Размер 54 слота
 * - Стекло только в Header (0-8) и Row 1 (9-17)
 * - Рабочая зона (18-44) без стекла, боковые слоты пустые (AIR)
 * - Пагинация: слот 36 (←) и слот 44 (→)
 * - Footer (45-53): стекло, слот 49 — «Выставить лот», слот 53 — «Закрыть»
 */
public class CommissionAgentGui implements InventoryHolder {

    public static final String TITLE = "Комиссионер";
    public static final String LOT_ID_KEY = "commission_lot_id";

    public static final int SLOT_INFO = 4;
    public static final int SLOT_MY_LOT = 6;
    public static final int SLOT_PREV_PAGE = 36;
    public static final int SLOT_NEXT_PAGE = 44;
    public static final int SLOT_CREATE_LOT = 49;
    public static final int SLOT_CLOSE = 53;

    public static final int[] CONTENT_SLOTS = new int[]{
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final LoveShops plugin;
    private final Player player;
    private final CommissionManager manager;
    private final int page;
    private Inventory inventory;

    public CommissionAgentGui(LoveShops plugin, Player player, CommissionManager manager, int page) {
        this.plugin = plugin;
        this.player = player;
        this.manager = manager;
        this.page = Math.max(0, page);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, 54, Component.text(TITLE).color(NamedTextColor.GOLD));
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
        inventory.setItem(0, GuiUtils.createPlayerProfileHead(player));

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        int feePercent = manager.getFeePercent();

        ItemStack infoItem = GuiUtils.createCustomHead(
                HeadTextures.BUTTON_BACK,
                "<gold><bold>⚖ Комиссионный брокер</bold></gold>",
                List.of(
                        "",
                        "<gray>Покупайте товары других игроков и выставляйте свои!</gray>",
                        "<gray>Комиссия брокера при продаже: <yellow>" + feePercent + "%</yellow></gray>",
                        "<gray>Лимит активных лотов: <yellow>" + manager.getMaxLotsPerPlayer() + " шт.</yellow></gray>",
                        "<gray>Оплата и получение: исключительно физические монеты.</gray>"
                )
        );
        inventory.setItem(SLOT_INFO, infoItem);

        // Кнопка «Мой лот» (слот 6)
        Optional<CommissionLot> myLotOpt = manager.getPlayerActiveLot(player.getUniqueId());
        if (myLotOpt.isPresent()) {
            CommissionLot myLot = myLotOpt.get();
            ItemStack myLotBtn = myLot.item().clone();
            ItemMeta myMeta = myLotBtn.getItemMeta();
            if (myMeta != null) {
                myMeta.displayName(MessageUtils.parse("<green><bold>Ваш активный лот</bold></green>"));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<gray>Цена: </gray>" + CoinFormat.formatGlyphs(eco, myLot.price())));
                lore.add(MessageUtils.parse("<gray>Вы получите при продаже: </gray>" + CoinFormat.formatGlyphs(eco, myLot.sellerReceives())));
                if (myLot.hot()) {
                    lore.add(MessageUtils.parse("<red><bold>🔥 ГОРЯЧЕЕ ПРЕДЛОЖЕНИЕ</bold></red>"));
                }
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<yellow>Нажмите, чтобы снять лот и забрать предмет.</yellow>"));
                myMeta.lore(lore);
                myLotBtn.setItemMeta(myMeta);
            }
            inventory.setItem(SLOT_MY_LOT, myLotBtn);
        } else {
            inventory.setItem(SLOT_MY_LOT, GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_BACK,
                    "<gray><bold>У вас нет активного лота</bold></gray>",
                    List.of("", "<gray>Нажмите <white>[Выставить лот]</white> внизу,</gray>", "<gray>держа предмет для продажи в руке.</gray>")
            ));
        }

        // 2. Row 1 Header (слоты 9-17) - всегда 100% стекло
        for (int i = 9; i <= 17; i++) {
            inventory.setItem(i, filler);
        }

        // 3. Рабочая зона (слоты 18-44)
        // В рабочей зоне стекла НЕТ (RULE 6). Боковые слоты (18, 26, 27, 35, 36, 44) остаются AIR,
        // кроме пагинации на слотах 36 и 44.
        int pageSize = CONTENT_SLOTS.length;
        List<CommissionLot> lots = manager.getActiveLots(page, pageSize);
        int totalCount = manager.getActiveLotsCount();

        for (int i = 0; i < lots.size() && i < CONTENT_SLOTS.length; i++) {
            CommissionLot lot = lots.get(i);
            int slot = CONTENT_SLOTS[i];

            ItemStack displayItem = lot.item().clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());

                if (lot.hot()) {
                    lore.add(MessageUtils.parse("<red><bold>🔥 ГОРЯЧЕЕ ПРЕДЛОЖЕНИЕ!</bold></red>"));
                    lore.add(Component.empty());
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }

                String sellerName = Bukkit.getOfflinePlayer(lot.sellerUuid()).getName();
                if (sellerName == null) sellerName = "Игрок";

                lore.add(MessageUtils.parse("<gray>Продавец: <gold>" + sellerName + "</gold></gray>"));
                lore.add(MessageUtils.parse("<gray>Цена: </gray>" + CoinFormat.formatGlyphs(eco, lot.price())));
                lore.add(Component.empty());

                if (player.getUniqueId().equals(lot.sellerUuid())) {
                    lore.add(MessageUtils.parse("<yellow><bold>(Ваш лот)</bold> Нажмите для снятия</yellow>"));
                } else {
                    lore.add(MessageUtils.parse("<green><bold>ЛКМ</bold> — купить предмет</green>"));
                }

                meta.lore(lore);
                meta.getPersistentDataContainer().set(new NamespacedKey(plugin, LOT_ID_KEY), PersistentDataType.INTEGER, lot.id());
                displayItem.setItemMeta(meta);
            }
            inventory.setItem(slot, displayItem);
        }

        // Пагинация на слотах 36 и 44 (Исключение 2)
        if (page > 0) {
            inventory.setItem(SLOT_PREV_PAGE, GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_ARROW_LEFT,
                    "<gold>← Предыдущая страница</gold>",
                    List.of("", "<gray>Страница " + page + "</gray>")
            ));
        }

        if ((page + 1) * pageSize < totalCount) {
            inventory.setItem(SLOT_NEXT_PAGE, GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_ARROW_RIGHT,
                    "<gold>Следующая страница →</gold>",
                    List.of("", "<gray>Страница " + (page + 2) + "</gray>")
            ));
        }

        // 4. Footer (слоты 45-53)
        for (int i = 45; i <= 53; i++) {
            inventory.setItem(i, filler);
        }

        // Слот 49: Выставить лот
        ItemStack createBtn = GuiUtils.createCustomHead(
                HeadTextures.BUTTON_PLUS,
                "<gold><bold>+ Выставить лот</bold></gold>",
                List.of(
                        "",
                        "<gray>Возьмите предмет в руку и нажмите сюда,</gray>",
                        "<gray>чтобы указать цену и выставить на комиссию.</gray>",
                        "",
                        "<gray>Лимит: <yellow>1 лот</yellow> на игрока.</gray>",
                        "<gray>Комиссия при продаже: <yellow>" + feePercent + "%</yellow></gray>"
                )
        );
        inventory.setItem(SLOT_CREATE_LOT, createBtn);

        // Слот 53: Закрыть
        inventory.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню комиссионера</gray>")
        ));
    }

    public static void handleClick(LoveShops plugin, Player player, int rawSlot, ClickType clickType, Inventory openInv) {
        if (!(openInv.getHolder() instanceof CommissionAgentGui gui)) return;

        CommissionManager manager = plugin.getCommissionManager();
        if (manager == null || !manager.isEnabled()) {
            player.closeInventory();
            MessageUtils.sendMessage(player, "<red>Комиссионный брокер временно недоступен.</red>");
            return;
        }

        // Закрыть
        if (rawSlot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        // Предыдущая страница
        if (rawSlot == SLOT_PREV_PAGE && gui.page > 0) {
            new CommissionAgentGui(plugin, player, manager, gui.page - 1).open();
            return;
        }

        // Следующая страница
        if (rawSlot == SLOT_NEXT_PAGE) {
            new CommissionAgentGui(plugin, player, manager, gui.page + 1).open();
            return;
        }

        // Кнопка «Мой лот»
        if (rawSlot == SLOT_MY_LOT) {
            Optional<CommissionLot> myLotOpt = manager.getPlayerActiveLot(player.getUniqueId());
            if (myLotOpt.isPresent()) {
                new CommissionConfirmGui(plugin, player, myLotOpt.get(), CommissionConfirmGui.ConfirmAction.CANCEL_OWN).open();
            } else {
                MessageUtils.sendMessage(player, "<yellow>У вас нет активных лотов на комиссии. Нажмите [+ Выставить лот]!</yellow>");
            }
            return;
        }

        // Кнопка «Выставить лот»
        if (rawSlot == SLOT_CREATE_LOT) {
            handleCreateLotClick(plugin, player, manager);
            return;
        }

        // Клик по лоту в рабочей зоне
        ItemStack clicked = openInv.getItem(rawSlot);
        if (clicked != null && clicked.hasItemMeta()) {
            Integer lotId = clicked.getItemMeta().getPersistentDataContainer()
                    .get(new NamespacedKey(plugin, LOT_ID_KEY), PersistentDataType.INTEGER);
            if (lotId != null) {
                Optional<CommissionLot> lotOpt = manager.getLotById(lotId);
                if (lotOpt.isEmpty() || !"ACTIVE".equalsIgnoreCase(lotOpt.get().status())) {
                    MessageUtils.sendMessage(player, "<red>Этот лот уже был продан или снят.</red>");
                    gui.render();
                    return;
                }

                CommissionLot lot = lotOpt.get();
                if (player.getUniqueId().equals(lot.sellerUuid())) {
                    // Свой собственный лот -> подтверждение снятия
                    new CommissionConfirmGui(plugin, player, lot, CommissionConfirmGui.ConfirmAction.CANCEL_OWN).open();
                } else {
                    // Чужой лот -> подтверждение покупки
                    new CommissionConfirmGui(plugin, player, lot, CommissionConfirmGui.ConfirmAction.BUY).open();
                }
            }
        }
    }

    private static void handleCreateLotClick(LoveShops plugin, Player player, CommissionManager manager) {
        if (manager.getPlayerActiveLot(player.getUniqueId()).isPresent()) {
            MessageUtils.sendMessage(player, "<red>У вас уже есть активный лот! Дождитесь его продажи или снимите его.</red>");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            MessageUtils.sendMessage(player, "<red>Возьмите предмет, который хотите продать, в главную руку!</red>");
            return;
        }

        if (plugin.getForbiddenManager().isForbidden(held.getType())) {
            MessageUtils.sendMessage(player, "<red>Этот предмет запрещено выставлять на продажу!</red>");
            return;
        }

        ItemStack itemToSell = held.clone();
        player.closeInventory();

        MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");
        MessageUtils.sendMessage(player, "<yellow>Выставление лота: <white>" + itemToSell.getType().name() + " x" + itemToSell.getAmount() + "</white></yellow>");
        MessageUtils.sendMessage(player, "<gray>Введите в чат желаемую общую цену в монетах (или <red>отмена</red>):</gray>");
        MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");

        if (plugin.getChatPromptService() != null) {
            plugin.getChatPromptService().ask(player, text -> {
                int price;
                try {
                    price = Integer.parseInt(text.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    MessageUtils.sendMessage(player, "<red>Неверный формат числа! Создание лота отменено.</red>");
                    return;
                }

                if (price <= 0 || price > 100_000_000) {
                    MessageUtils.sendMessage(player, "<red>Цена должна быть от 1 до 100 000 000 монет!</red>");
                    return;
                }

                // Проверяем, держит ли игрок всё ещё тот же предмет
                ItemStack currentHeld = player.getInventory().getItemInMainHand();
                if (!currentHeld.isSimilar(itemToSell) || currentHeld.getAmount() < itemToSell.getAmount()) {
                    MessageUtils.sendMessage(player, "<red>Предмет в руке изменился! Создание лота отменено.</red>");
                    return;
                }

                // Снимаем предмет из руки
                int remaining = currentHeld.getAmount() - itemToSell.getAmount();
                if (remaining > 0) {
                    currentHeld.setAmount(remaining);
                } else {
                    player.getInventory().setItemInMainHand(null);
                }
                player.updateInventory();

                CommissionManager.LotResult result = manager.createLot(player, itemToSell, price);
                if (result != CommissionManager.LotResult.SUCCESS) {
                    // Возврат предмета в случае ошибки
                    var leftovers = player.getInventory().addItem(itemToSell);
                    for (ItemStack drop : leftovers.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), drop);
                    }
                    switch (result) {
                        case ALREADY_HAS_LOT -> MessageUtils.sendMessage(player, "<red>У вас уже есть активный лот!</red>");
                        case FORBIDDEN_ITEM -> MessageUtils.sendMessage(player, "<red>Этот предмет запрещено продавать.</red>");
                        default -> MessageUtils.sendMessage(player, "<red>Ошибка при выставлении лота.</red>");
                    }
                } else {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            new CommissionAgentGui(plugin, player, manager, 0).open();
                        }
                    }, 5L);
                }
            }, () -> {
                MessageUtils.sendMessage(player, "<gray>Выставление лота отменено.</gray>");
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        new CommissionAgentGui(plugin, player, manager, 0).open();
                    }
                }, 2L);
            });
        }
    }
}
