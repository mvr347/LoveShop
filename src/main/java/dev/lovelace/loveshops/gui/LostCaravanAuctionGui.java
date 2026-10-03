package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.LostCaravanManager;
import dev.lovelace.loveshops.models.caravan.LostCaravanLot;
import dev.lovelace.loveshops.models.caravan.LostCaravanSession;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI Аукциона Потерянного Каравана (45 слотов) по стандарту gui-gen-5:
 * - Header (0-8): профиль игрока (0), статус аукциона (4), стекло (1-3, 5-8)
 * - Row 1 (9-17): 100% стекло
 * - Рабочая зона (18-35): без стекла!
 *   - Слот 22: Текущий ящик (превью, текущая ставка, таймер)
 *   - Слоты 29, 30, 31: Быстрые ставки (+10, +50, +100)
 *   - Слот 33: Статус игрока (лидер / перебит)
 * - Footer (36-44): стекло, слот 40 — «Своя ставка», слот 44 — «Закрыть»
 */
public class LostCaravanAuctionGui implements InventoryHolder {

    public static final String TITLE = "Аукцион Каравана";

    public static final int SLOT_INFO = 0;
    public static final int SLOT_LOT_PREVIEW = 22;
    public static final int SLOT_BID_10 = 29;
    public static final int SLOT_BID_50 = 30;
    public static final int SLOT_BID_100 = 31;
    public static final int SLOT_MY_STATUS = 33;
    public static final int SLOT_CUSTOM_BID = 40;
    public static final int SLOT_CLOSE = 44;

    private final LoveShops plugin;
    private final Player player;
    private final LostCaravanManager manager;
    private Inventory inventory;

    public LostCaravanAuctionGui(LoveShops plugin, Player player, LostCaravanManager manager) {
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

        LostCaravanSession session = manager.getCurrentSession();
        LostCaravanLot lot = manager.getCurrentAuctionLot();
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        long timeRem = manager.getCurrentLotTimeRemainingSeconds();
        int lotIdx = lot != null ? (lot.lotIndex() + 1) : 0;
        int totalLots = manager.getActiveLots().size();

        ItemStack infoItem = GuiUtils.createCustomHead(
                HeadTextures.BUTTON_BACK,
                "<gold>⚔ Торги Каравана</gold>",
                List.of(
                        "",
                        "<gray>Текущий лот: <yellow>" + lotIdx + " из " + totalLots + "</yellow></gray>",
                        "<gray>Осталось времени на лот: <gold>" + timeRem + " сек.</gold></gray>",
                        "<gray>Участников каравана: <yellow>" + (session != null ? session.participantCount() : 0) + "</yellow></gray>",
                        lot != null && lot.secret() ? "<red>⚡ ВНИМАНИЕ: ЭТО СЕКРЕТНЫЙ ЯЩИК!</red>" : ""
                )
        );
        inventory.setItem(SLOT_INFO, infoItem);

        // 2. Row 1 Header (слоты 9-17) - 100% стекло
        for (int i = 9; i <= 17; i++) {
            inventory.setItem(i, filler);
        }

        // 3. Рабочая зона (слоты 18-35)
        // В рабочей зоне стекла НЕТ (RULE 6).
        if (lot != null) {
            ItemStack preview = lot.crateItem().clone();
            ItemMeta meta = preview.getItemMeta();
            if (meta != null) {
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                if (lot.secret()) {
                    lore.add(MessageUtils.parse("<red>⚡ СЕКРЕТНЫЙ ЯЩИК КАРАВАНА</red>"));
                } else {
                    lore.add(MessageUtils.parse("<gold>📦 Ящик каравана #" + lotIdx + "</gold>"));
                }
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<gray>Начальная цена: </gray>" + CoinFormat.formatGlyphs(eco, lot.startingPrice())));

                if (lot.currentBid() > 0) {
                    lore.add(MessageUtils.parse("<gray>Текущая ставка: </gray>" + CoinFormat.formatGlyphs(eco, lot.currentBid())));
                    String bidderName = lot.highestBidder() != null ? Bukkit.getOfflinePlayer(lot.highestBidder()).getName() : "Нет";
                    lore.add(MessageUtils.parse("<gray>Лидирует: <yellow>" + bidderName + "</yellow></gray>"));
                } else {
                    lore.add(MessageUtils.parse("<gray>Ставок пока нет</gray>"));
                }
                lore.add(Component.empty());
                lore.add(MessageUtils.parse("<gray>Осталось: <gold>" + timeRem + " сек.</gold></gray>"));
                meta.lore(lore);
                preview.setItemMeta(meta);
            }
            inventory.setItem(SLOT_LOT_PREVIEW, preview);

            // Кнопки быстрых ставок
            int current = Math.max(lot.startingPrice(), lot.currentBid());
            inventory.setItem(SLOT_BID_10, GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_PLUS,
                    "<green>+10 монет</green>",
                    List.of("", "<gray>Поставить: </gray>" + CoinFormat.formatGlyphs(eco, current + 10), "", "<yellow>Нажмите для ставки</yellow>")
            ));

            inventory.setItem(SLOT_BID_50, GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_PLUS,
                    "<gold>+50 монет</gold>",
                    List.of("", "<gray>Поставить: </gray>" + CoinFormat.formatGlyphs(eco, current + 50), "", "<yellow>Нажмите для ставки</yellow>")
            ));

            inventory.setItem(SLOT_BID_100, GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_PLUS,
                    "<yellow>+100 монет</yellow>",
                    List.of("", "<gray>Поставить: </gray>" + CoinFormat.formatGlyphs(eco, current + 100), "", "<yellow>Нажмите для ставки</yellow>")
            ));

            // Слот 33: Статус игрока
            boolean isLeading = player.getUniqueId().equals(lot.highestBidder());
            if (isLeading) {
                inventory.setItem(SLOT_MY_STATUS, GuiUtils.createCustomHead(
                        HeadTextures.HEAD_CONFIRM,
                        "<green>Вы лидируете!</green>",
                        List.of("", "<gray>Ваша ставка в размере </gray>" + CoinFormat.formatGlyphs(eco, lot.currentBid()) + "<gray> является наивысшей.</gray>")
                ));
            } else {
                inventory.setItem(SLOT_MY_STATUS, GuiUtils.createCustomHead(
                        HeadTextures.HEAD_DELETE_NO,
                        "<red>Вы не лидируете</red>",
                        List.of("", "<gray>Сделайте ставку, чтобы побороться за этот ящик!</gray>")
                ));
            }
        }

        // 4. Footer (слоты 36-44)
        for (int i = 36; i <= 44; i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(SLOT_CUSTOM_BID, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_BACK,
                "<gold>Своя сумма ставки</gold>",
                List.of("", "<gray>Нажмите, чтобы ввести точную сумму ставки в чат.</gray>")
        ));

        inventory.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню аукциона</gray>")
        ));
    }

    public static void handleClick(LoveShops plugin, Player player, int rawSlot, ClickType clickType, Inventory openInv) {
        if (!(openInv.getHolder() instanceof LostCaravanAuctionGui gui)) return;

        if (rawSlot == SLOT_CLOSE) {
            player.closeInventory();
            return;
        }

        LostCaravanManager manager = plugin.getLostCaravanManager();
        if (manager == null || !manager.isAuctionPhase()) {
            MessageUtils.sendMessage(player, "<red>Аукцион сейчас не активен.</red>");
            player.closeInventory();
            return;
        }

        LostCaravanLot lot = manager.getCurrentAuctionLot();
        if (lot == null) {
            MessageUtils.sendMessage(player, "<red>Нет активного лота.</red>");
            gui.render();
            return;
        }

        int base = Math.max(lot.startingPrice(), lot.currentBid());

        if (rawSlot == SLOT_BID_10) {
            report(player, manager.placeBid(player, base + 10));
            refreshAll(plugin);
            return;
        }

        if (rawSlot == SLOT_BID_50) {
            report(player, manager.placeBid(player, base + 50));
            refreshAll(plugin);
            return;
        }

        if (rawSlot == SLOT_BID_100) {
            report(player, manager.placeBid(player, base + 100));
            refreshAll(plugin);
            return;
        }

        if (rawSlot == SLOT_CUSTOM_BID) {
            player.closeInventory();
            MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");
            MessageUtils.sendMessage(player, "<yellow>Текущая ставка: <white>" + lot.currentBid() + " монет</white></yellow>");
            MessageUtils.sendMessage(player, "<gray>Введите желаемую ставку в чат (или <red>отмена</red>):</gray>");
            MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");

            if (plugin.getChatPromptService() != null) {
                plugin.getChatPromptService().ask(player, text -> {
                    int amount;
                    try {
                        amount = Integer.parseInt(text.replaceAll("[^0-9]", ""));
                    } catch (NumberFormatException e) {
                        MessageUtils.sendMessage(player, "<red>Неверный формат ставки!</red>");
                        return;
                    }

                    LostCaravanManager.BidResult result = manager.placeBid(player, amount);
                    if (result != LostCaravanManager.BidResult.SUCCESS) {
                        switch (result) {
                            case TOO_LOW -> MessageUtils.sendMessage(player, "<red>Ставка слишком мала! Она должна превышать текущую.</red>");
                            case NO_MONEY -> MessageUtils.sendMessage(player, "<red>У вас недостаточно монет для такой ставки!</red>");
                            case ALREADY_HIGHEST -> MessageUtils.sendMessage(player, "<yellow>Вы уже лидируете в торгах!</yellow>");
                            case NOT_REGISTERED -> MessageUtils.sendMessage(player, "<red>Вы не вносили залог для участия!</red>");
                            default -> MessageUtils.sendMessage(player, "<red>Не удалось сделать ставку.</red>");
                        }
                    }

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            new LostCaravanAuctionGui(plugin, player, manager).open();
                        }
                    }, 5L);
                }, () -> {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            new LostCaravanAuctionGui(plugin, player, manager).open();
                        }
                    }, 2L);
                });
            }
        }
    }

    /** Tells the player why a quick bid did not go through (it used to fail silently). */
    private static void report(Player player, LostCaravanManager.BidResult result) {
        switch (result) {
            case TOO_LOW -> MessageUtils.sendMessage(player, "<red>Ставка слишком мала: нужно хотя бы на 5% выше текущей.</red>");
            case NO_MONEY -> MessageUtils.sendMessage(player, "<red>У вас недостаточно монет для такой ставки!</red>");
            case ALREADY_HIGHEST -> MessageUtils.sendMessage(player, "<yellow>Вы уже лидируете в торгах!</yellow>");
            case NOT_REGISTERED -> MessageUtils.sendMessage(player, "<red>Вы не вносили залог для участия!</red>");
            case AUCTION_NOT_ACTIVE -> MessageUtils.sendMessage(player, "<red>Торги за этот лот уже закончились.</red>");
            case DB_ERROR -> MessageUtils.sendMessage(player, "<red>Не удалось сохранить ставку, монеты возвращены.</red>");
            default -> { }
        }
    }

    public static void refresh(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof LostCaravanAuctionGui gui) {
            gui.render();
        }
    }

    public static void refreshAll(LoveShops plugin) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof LostCaravanAuctionGui gui) {
                gui.render();
            }
        }
    }
}
