package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.CommissionManager;
import dev.lovelace.loveshops.models.commission.CommissionLot;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.market.gui.PriceGui;
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
 * - Footer (45-53): стекло, слот 52 — «Выставить лот» (или ваш активный лот), слот 53 — «Закрыть»
 */
public class CommissionAgentGui implements InventoryHolder {

    public static final String TITLE = "Комиссионер";
    public static final String LOT_ID_KEY = "commission_lot_id";

    public static final int SLOT_INFO = 0;
    public static final int SLOT_PREV_PAGE = 36;
    public static final int SLOT_NEXT_PAGE = 44;
    public static final int SLOT_CREATE_LOT = 52;
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

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        int feePercent = manager.getFeePercent();

        ItemStack infoItem = GuiUtils.createCustomHead(
                HeadTextures.BUTTON_BACK,
                "<gold>⚖ Комиссионный брокер</gold>",
                List.of(
                        "",
                        "<gray>Покупайте товары других игроков и выставляйте свои!</gray>",
                        "<gray>Комиссия брокера при продаже: <yellow>" + feePercent + "%</yellow></gray>",
                        "<gray>Лимит активных лотов: <yellow>" + manager.getMaxLotsPerPlayer() + " шт.</yellow></gray>",
                        "<gray>Оплата и получение: исключительно физические монеты.</gray>"
                )
        );
        inventory.setItem(SLOT_INFO, infoItem);

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

        if (lots.isEmpty()) {
            inventory.setItem(31, GuiUtils.emptyCard("<gray>Выставьте первый лот — кнопка внизу.</gray>"));
        }
        for (int i = 0; i < lots.size() && i < CONTENT_SLOTS.length; i++) {
            CommissionLot lot = lots.get(i);
            int slot = CONTENT_SLOTS[i];

            ItemStack displayItem = lot.item().clone();
            ItemMeta meta = displayItem.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());

                if (lot.hot()) {
                    lore.add(MessageUtils.parse("<red>🔥 ГОРЯЧЕЕ ПРЕДЛОЖЕНИЕ!</red>"));
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
                    lore.add(MessageUtils.parse("<yellow>(Ваш лот) Нажмите для снятия</yellow>"));
                } else {
                    lore.add(MessageUtils.parse("<green>ЛКМ — купить предмет</green>"));
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

        // Слот 52: «Ваш активный лот» (если выставлен) или «Выставить лот»
        Optional<CommissionLot> myLotOpt = manager.getPlayerActiveLot(player.getUniqueId());
        if (myLotOpt.isPresent()) {
            CommissionLot myLot = myLotOpt.get();
            ItemStack myLotBtn = myLot.item().clone();
            ItemMeta myMeta = myLotBtn.getItemMeta();
            if (myMeta != null) {
                myMeta.displayName(MessageUtils.parse("<green>Ваш активный лот</green>"));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<gray>Цена:</gray>"));
                lore.add(MessageUtils.parse(CoinFormat.formatGlyphs(eco, myLot.price())));
                lore.add(MessageUtils.parse("<gray>Вы получите при продаже:</gray>"));
                lore.add(MessageUtils.parse(CoinFormat.formatGlyphs(eco, myLot.sellerReceives())));
                if (myLot.hot()) {
                    lore.add(MessageUtils.parse("<red>🔥 Горячее предложение</red>"));
                }
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<yellow>ЛКМ </yellow><gray>— снять лот и забрать предмет</gray>"));
                myMeta.lore(lore);
                myLotBtn.setItemMeta(myMeta);
            }
            inventory.setItem(SLOT_CREATE_LOT, myLotBtn);
        } else {
            inventory.setItem(SLOT_CREATE_LOT, GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_PLUS,
                    "<gold>Выставить лот</gold>",
                    List.of(
                            "",
                            "<gray>Перетащите сюда предмет или нажмите</gray>",
                            "<yellow>Shift</yellow><gray> на предмете в инвентаре.</gray>",
                            "",
                            "<gray>Лимит: <yellow>" + manager.getMaxLotsPerPlayer() + "</yellow> лот на игрока.</gray>",
                            "<gray>Комиссия при продаже: <yellow>" + feePercent + "%</yellow></gray>"
                    )
            ));
        }

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

        // Слот 52: свой лот (снять) или «Выставить лот»
        if (rawSlot == SLOT_CREATE_LOT) {
            Optional<CommissionLot> myLotOpt = manager.getPlayerActiveLot(player.getUniqueId());
            if (myLotOpt.isPresent()) {
                new CommissionConfirmGui(plugin, player, myLotOpt.get(), CommissionConfirmGui.ConfirmAction.CANCEL_OWN).open();
            } else {
                MessageUtils.sendMessage(player, "<gray>Перетащите предмет на кнопку «Выставить лот» или нажмите Shift на предмете в инвентаре.</gray>");
            }
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

    /**
     * Открывает меню цены (как ставка в LoveDuels): Shift — смена монеты, ЛКМ/ПКМ — ±1 монета.
     * @param takeFromHand если true — снимает предмет с главной руки после подтверждения
     */
    public static void startListing(LoveShops plugin, Player player, CommissionManager manager,
                                    ItemStack itemToSell, boolean takeFromHand) {
        if (manager.getPlayerActiveLot(player.getUniqueId()).isPresent()) {
            MessageUtils.sendMessage(player, "<red>У вас уже есть активный лот! Сначала снимите его.</red>");
            if (!takeFromHand) giveBack(player, itemToSell);
            return;
        }
        if (itemToSell == null || itemToSell.getType().isAir() || itemToSell.getAmount() <= 0) {
            MessageUtils.sendMessage(player, "<gray>Нечего выставлять.</gray>");
            return;
        }
        if (plugin.getForbiddenManager().isForbidden(itemToSell.getType())) {
            MessageUtils.sendMessage(player, "<red>Этот предмет запрещено выставлять.</red>");
            if (!takeFromHand) giveBack(player, itemToSell);
            return;
        }

        final ItemStack listing = itemToSell.clone();
        player.closeInventory();

        new PriceGui(plugin, player, listing, false, (amount, price) -> {
            ItemStack sell = listing.clone();
            sell.setAmount(Math.max(1, Math.min(amount, listing.getAmount())));

            if (takeFromHand) {
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (!hand.isSimilar(listing) || hand.getAmount() < sell.getAmount()) {
                    MessageUtils.sendMessage(player, "<red>Предмет в руке изменился. Лог отменён.</red>");
                    return;
                }
                int left = hand.getAmount() - sell.getAmount();
                if (left > 0) hand.setAmount(left);
                else player.getInventory().setItemInMainHand(null);
                player.updateInventory();
            }

            long total = price * (long) sell.getAmount();
            if (total <= 0 || total > 100_000_000L) {
                giveBack(player, sell);
                MessageUtils.sendMessage(player, "<red>Некорректная цена.</red>");
                return;
            }

            CommissionManager.LotResult result = manager.createLot(player, sell, (int) total);
            if (result != CommissionManager.LotResult.SUCCESS) {
                giveBack(player, sell);
                switch (result) {
                    case ALREADY_HAS_LOT -> MessageUtils.sendMessage(player, "<red>У вас уже есть активный лот!</red>");
                    case FORBIDDEN_ITEM -> MessageUtils.sendMessage(player, "<red>Этот предмет запрещено продавать.</red>");
                    default -> MessageUtils.sendMessage(player, "<red>Не удалось выставить лот.</red>");
                }
            } else {
                MessageUtils.sendMessage(player, "<green>Лот выставлен.</green>");
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    new CommissionAgentGui(plugin, player, manager, 0).open();
                }
            }, 3L);
        }, () -> {
            if (!takeFromHand) {
                giveBack(player, listing);
            }
            MessageUtils.sendMessage(player, "<gray>Выставление отменено.</gray>");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    new CommissionAgentGui(plugin, player, manager, 0).open();
                }
            }, 2L);
        }).open();
    }

    private static void giveBack(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        var leftovers = player.getInventory().addItem(item);
        for (ItemStack drop : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }

}
