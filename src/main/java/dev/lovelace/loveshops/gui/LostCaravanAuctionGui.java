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
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GUI Аукциона Потерянного Каравана (54 слота) по стандарту gui-gen-5 v2.1:
 * - Header (0-8): тематическая голова в слоте 0 (инфо о торгах), остальное стекло; кнопок управления нет.
 * - Row 1 (9-17): 100% стекло
 * - Рабочая зона (18-44): без стекла, боковые стенки (18, 26, 27, 35, 36, 44) пусты, пагинации нет
 *   - Ряд 1: 19 — таймер, 22 — лот, 25 — статус игрока
 *   - Ряд 2: 28 — лидер, 29 — минимальная ставка, 30-32 — быстрые ставки (проценты), 33 — своя сумма
 *   - Ряд 3: 37-39 — очередь следующих лотов, 41-43 — последние ставки по лоту
 * - Footer (45-53): стекло, слот 53 — «Закрыть» (Д и Back не используются: стекло)
 */
public class LostCaravanAuctionGui implements InventoryHolder {

    public static final String TITLE = "Аукцион Каравана";

    public static final int SIZE = 54;
    public static final int SLOT_INFO = 0;
    public static final int SLOT_TIMER = 19;
    public static final int SLOT_LOT_PREVIEW = 22;
    public static final int SLOT_MY_STATUS = 25;
    public static final int SLOT_LEADER = 28;
    public static final int SLOT_MIN_BID = 29;
    public static final int SLOT_BID_1 = 30;
    public static final int SLOT_BID_2 = 31;
    public static final int SLOT_BID_3 = 32;
    public static final int SLOT_CUSTOM_BID = 33;
    public static final int[] SLOTS_QUEUE = {37, 38, 39};
    public static final int[] SLOTS_RECENT_BIDS = {41, 42, 43};
    public static final int SLOT_CLOSE = 53;

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
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(TITLE).color(NamedTextColor.GOLD));
        render();
        player.openInventory(inventory);
    }

    public void render() {
        inventory.clear();
        ItemStack filler = GuiUtils.createFiller();

        // Header (0-8) and Row 1 (9-17): glass only; slot 0 is the themed info head
        for (int i = 0; i <= 17; i++) {
            inventory.setItem(i, filler);
        }
        // Footer (45-53): glass, the close button replaces the glass at 53
        for (int i = 45; i <= 53; i++) {
            inventory.setItem(i, filler);
        }

        LostCaravanSession session = manager.getCurrentSession();
        LostCaravanLot lot = manager.getCurrentAuctionLot();
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        long timeRem = manager.getCurrentLotTimeRemainingSeconds();
        int lotIdx = lot != null ? (lot.lotIndex() + 1) : 0;
        int totalLots = manager.getActiveLots().size();

        inventory.setItem(SLOT_INFO, GuiUtils.createCustomHead(
                HeadTextures.CARAVAN_LOST_INFO,
                "<gold>⚔ Торги Каравана</gold>",
                List.of(
                        "",
                        "<gray>Текущий лот: <yellow>" + lotIdx + " из " + totalLots + "</yellow></gray>",
                        "<gray>Участников каравана: <yellow>" + (session != null ? session.participantCount() : 0) + "</yellow></gray>",
                        "",
                        "<gray>Ставка возвращается, если вас перебили.</gray>",
                        "<gray>Ставка в последние <yellow>" + manager.antiSnipeThresholdSeconds()
                                + " сек.</yellow> продлевает торги.</gray>"
                )
        ));

        if (lot != null) {
            renderLot(lot, lotIdx, timeRem, eco);
            renderBidButtons(lot, eco);
            renderStatus(lot, eco);
            renderQueue(eco);
            renderRecentBids(lot, eco);
        }

        inventory.setItem(SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                "<red>Закрыть</red>",
                List.of("", "<gray>Выход из меню аукциона</gray>")
        ));
    }

    private void renderLot(LostCaravanLot lot, int lotIdx, long timeRem, LoveEconomy eco) {
        // Timer card with a progress bar of the lot's bidding window
        inventory.setItem(SLOT_TIMER, GuiUtils.createCustomHead(
                HeadTextures.CARAVAN_LOST_TIMER,
                "<gold>⏱ Осталось: " + timeRem + " сек.</gold>",
                List.of("", timeBar(timeRem), "",
                        "<gray>Ставка в последние <yellow>" + manager.antiSnipeThresholdSeconds()
                                + " сек.</yellow> добавляет <yellow>" + manager.antiSnipeExtendSeconds() + " сек.</yellow></gray>")
        ));

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
            } else {
                lore.add(MessageUtils.parse("<gray>Ставок пока нет</gray>"));
            }
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        inventory.setItem(SLOT_LOT_PREVIEW, preview);
    }

    private void renderBidButtons(LostCaravanLot lot, LoveEconomy eco) {
        boolean leading = player.getUniqueId().equals(lot.highestBidder());

        if (lot.currentBid() > 0 && lot.highestBidder() != null) {
            inventory.setItem(SLOT_LEADER, leaderHead(lot, eco));
        } else {
            inventory.setItem(SLOT_LEADER, GuiUtils.createCustomHead(
                    HeadTextures.BANKER_DEPOSIT_EMPTY,
                    "<gray>Лидера пока нет</gray>",
                    List.of("", "<gray>Сделайте первую ставку — от начальной цены.</gray>")));
        }

        inventory.setItem(SLOT_MIN_BID, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_PLUS,
                "<green>Минимальная ставка</green>",
                List.of("", "<gray>Поставить: </gray>" + CoinFormat.formatGlyphs(eco, manager.minimumBid(lot)), "",
                        leading ? "<gray>Вы уже лидируете.</gray>" : "<yellow>Нажмите для ставки</yellow>")));

        int[] slots = {SLOT_BID_1, SLOT_BID_2, SLOT_BID_3};
        String[] colors = {"green", "gold", "yellow"};
        for (int i = 0; i < slots.length; i++) {
            inventory.setItem(slots[i], GuiUtils.createCustomHead(
                    HeadTextures.BUTTON_PLUS,
                    "<" + colors[i] + ">+" + quickPercent(i) + "%</" + colors[i] + ">",
                    List.of("", "<gray>Поставить: </gray>" + CoinFormat.formatGlyphs(eco, quickBid(manager, lot, i)), "",
                            leading ? "<gray>Вы уже лидируете.</gray>" : "<yellow>Нажмите для ставки</yellow>")));
        }

        inventory.setItem(SLOT_CUSTOM_BID, GuiUtils.createCustomHead(
                HeadTextures.COMMISSION_PRICE,
                "<gold>Своя сумма ставки</gold>",
                List.of("", "<gray>Введите сумму в чат: число или, например,</gray>",
                        "<yellow>3i 50c</yellow> <gray>(c — медная, i — железная, g — золотая, d — алмазная).</gray>")));
    }

    private void renderStatus(LostCaravanLot lot, LoveEconomy eco) {
        if (player.getUniqueId().equals(lot.highestBidder())) {
            inventory.setItem(SLOT_MY_STATUS, GuiUtils.createCustomHead(
                    HeadTextures.HEAD_CONFIRM,
                    "<green>Вы лидируете!</green>",
                    List.of("", "<gray>Ваша ставка </gray>" + CoinFormat.formatGlyphs(eco, lot.currentBid()) + "<gray> наивысшая.</gray>")));
        } else {
            inventory.setItem(SLOT_MY_STATUS, GuiUtils.createCustomHead(
                    HeadTextures.HEAD_DELETE_NO,
                    "<red>Вы не лидируете</red>",
                    List.of("", "<gray>Сделайте ставку, чтобы побороться за этот ящик!</gray>")));
        }
    }

    /** Next lots in the queue (secret crates are shown without hinting what is inside). */
    private void renderQueue(LoveEconomy eco) {
        List<LostCaravanLot> upcoming = manager.getUpcomingLots(SLOTS_QUEUE.length);
        for (int i = 0; i < upcoming.size(); i++) {
            LostCaravanLot next = upcoming.get(i);
            inventory.setItem(SLOTS_QUEUE[i], GuiUtils.createCustomHead(
                    next.secret() ? HeadTextures.CARAVAN_LOST_SECRET : HeadTextures.CARAVAN_LOST_CRATE,
                    next.secret()
                            ? "<red>⚡ Секретный ящик #" + (next.lotIndex() + 1) + "</red>"
                            : "<gold>📦 Ящик #" + (next.lotIndex() + 1) + "</gold>",
                    List.of("", "<gray>Следующие торги.</gray>",
                            "<gray>Начальная цена: </gray>" + CoinFormat.formatGlyphs(eco, next.startingPrice()))));
        }
    }

    /** Newest-first bids on the current lot, shown as the bidder's head. */
    private void renderRecentBids(LostCaravanLot lot, LoveEconomy eco) {
        List<LostCaravanManager.BidEntry> bids = manager.getRecentBids(lot.id(), SLOTS_RECENT_BIDS.length);
        for (int i = 0; i < bids.size(); i++) {
            LostCaravanManager.BidEntry bid = bids.get(i);
            inventory.setItem(SLOTS_RECENT_BIDS[i], bidderHead(bid.bidder(),
                    "<yellow>" + bid.bidderName() + "</yellow>",
                    List.of("", "<gray>Ставка: </gray>" + CoinFormat.formatGlyphs(eco, bid.amount()),
                            "<gray>" + ago(bid.atMillis()) + "</gray>")));
        }
    }

    private ItemStack leaderHead(LostCaravanLot lot, LoveEconomy eco) {
        OfflinePlayer leader = Bukkit.getOfflinePlayer(lot.highestBidder());
        String name = leader.getName() != null ? leader.getName() : "—";
        return bidderHead(lot.highestBidder(), "<gold>🏆 Лидер: <white>" + name + "</white></gold>",
                List.of("", "<gray>Ставка: </gray>" + CoinFormat.formatGlyphs(eco, lot.currentBid())));
    }

    private ItemStack bidderHead(UUID uuid, String name, List<String> lore) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            meta.displayName(MessageUtils.parse(name));
            meta.lore(lore.stream().map(MessageUtils::parse).toList());
            head.setItemMeta(meta);
        }
        return head;
    }

    /** "[■■■■□□□□□□]" bar for the share of the bidding window that is left. */
    private String timeBar(long timeRem) {
        long total = Math.max(1, plugin.getConfig().getLong("caravan.lost.bid-duration-seconds", 60));
        int filled = (int) Math.max(0, Math.min(10, Math.round(10.0 * timeRem / total)));
        String color = filled <= 2 ? "<red>" : filled <= 5 ? "<yellow>" : "<green>";
        return color + "■".repeat(filled) + "</>" + "<dark_gray>" + "■".repeat(10 - filled) + "</dark_gray>";
    }

    private static String ago(long atMillis) {
        long sec = Math.max(0, (System.currentTimeMillis() - atMillis) / 1000);
        return sec < 60 ? sec + " сек. назад" : (sec / 60) + " мин. назад";
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

        if (rawSlot == SLOT_MIN_BID) {
            report(player, manager.placeBid(player, manager.minimumBid(lot)));
            refreshAll(plugin);
            return;
        }

        int quickIndex = rawSlot == SLOT_BID_1 ? 0 : rawSlot == SLOT_BID_2 ? 1 : rawSlot == SLOT_BID_3 ? 2 : -1;
        if (quickIndex >= 0) {
            report(player, manager.placeBid(player, quickBid(manager, lot, quickIndex)));
            refreshAll(plugin);
            return;
        }

        if (rawSlot == SLOT_CUSTOM_BID) {
            player.closeInventory();
            MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");
            MessageUtils.sendMessage(player, "<yellow>Текущая ставка: </yellow>" + CoinFormat.formatGlyphs(lot.currentBid()));
            MessageUtils.sendMessage(player, "<gray>Введите желаемую ставку в чат (или <red>отмена</red>):</gray>");
            MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");

            if (plugin.getChatPromptService() != null) {
                plugin.getChatPromptService().ask(player, text -> {
                    int amount;
                    try {
                        // a plain number (copper units) or money text such as "3i 50c" (c/i/g/d = copper/iron/gold/diamond)
                        long typed = dev.lovelace.loveshops.utils.Money.parse(text);
                        if (typed <= 0 || typed > Integer.MAX_VALUE) throw new IllegalArgumentException("out of range");
                        amount = (int) typed;
                    } catch (IllegalArgumentException e) {
                        MessageUtils.sendMessage(player, "<red>Неверный формат ставки! Число или, например, 3i 50c (c — медная, i — железная, g — золотая, d — алмазная).</red>");
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

    private static int quickPercent(int index) {
        List<Integer> percents = dev.lovelace.loveshops.LoveShops.getInstance().getConfig().getIntegerList("caravan.lost.quick-bid-percents");
        int[] defaults = {5, 15, 30};
        return index < percents.size() && percents.get(index) > 0 ? percents.get(index) : defaults[index];
    }

    /** The bid a quick button places: the current price plus the button's percent, never below the minimum bid. */
    private static int quickBid(LostCaravanManager manager, LostCaravanLot lot, int index) {
        int base = Math.max(lot.startingPrice(), lot.currentBid());
        int raise = Math.max(1, (int) Math.round(base * quickPercent(index) / 100.0));
        int bid = base + raise;
        if (lot.currentBid() > 0) {
            bid = Math.max(bid, lot.currentBid() + manager.minRaiseOver(lot.currentBid()));
        }
        return bid;
    }

    /** Tells the player why a quick bid did not go through (it used to fail silently). */
    private static void report(Player player, LostCaravanManager.BidResult result) {
        switch (result) {
            case TOO_LOW -> MessageUtils.sendMessage(player, "<red>Ставка слишком мала: нужно выше текущей минимум на шаг повышения (по умолчанию 5%).</red>");
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
