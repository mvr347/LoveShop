package dev.lovelace.loveshops.gui;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.LostCaravanManager;
import dev.lovelace.loveshops.models.caravan.LostCaravanLot;
import dev.lovelace.loveshops.models.caravan.LostCaravanSession;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.GuiUtils;
import dev.lovelace.loveshops.utils.ProgressBarUtil;
import dev.lovelace.loveshops.utils.LoreBlocks;
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
 *   - Ряд 1: 20 — таймер, 22 — лот, 24 — «Ставки» (лидер и следующие ставки одним списком, свой ник другого цвета)
 *   - Ряд 2: 29 — минимальная ставка, 30-32 — быстрые ставки (проценты), 33 — своя сумма
 *   - Ряд 3: 39-41 — очередь следующих лотов
 * - Footer (45-53): стекло, слот 53 — «Закрыть» (Д и Back не используются: стекло)
 */
public class LostCaravanAuctionGui implements InventoryHolder {

    public static final String TITLE = "Аукцион Каравана";

    public static final int SIZE = 54;
    public static final int SLOT_INFO = 0;
    public static final int SLOT_TIMER = 20;
    public static final int SLOT_LOT_PREVIEW = 22;
    public static final int SLOT_BIDS = 24;
    public static final int SLOT_MIN_BID = 29;
    public static final int SLOT_BID_1 = 30;
    public static final int SLOT_BID_2 = 31;
    public static final int SLOT_BID_3 = 32;
    public static final int SLOT_CUSTOM_BID = 33;
    public static final int[] SLOTS_QUEUE = {39, 40, 41};
    /** How many bidders after the leader the «Ставки» card lists (caravan.lost.bids-shown). */
    private static final int DEFAULT_BIDS_SHOWN = 4;
    private static final int[] WORK_ROWS = {19, 28, 37};
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

    private dev.lovelace.loveshops.managers.LangManager lang() {
        return plugin.getLangManager();
    }

    public void open() {
        this.inventory = Bukkit.createInventory(this, SIZE, Component.text(TITLE).color(NamedTextColor.GOLD));
        render();
        player.openInventory(inventory);
    }

    private void put(int slot, ItemStack item) {
        GuiUtils.putIfChanged(inventory, slot, item);
    }

    public void render() {
        ItemStack filler = GuiUtils.createFiller();

        // Header (0-8) and Row 1 (9-17): glass only; slot 0 is the themed info head
        for (int i = 0; i <= 17; i++) {
            put(i, filler);
        }
        // Footer (45-53): glass, the close button replaces the glass at 53
        for (int i = 45; i <= 53; i++) {
            put(i, filler);
        }
        // Work zone: empty unless a card below fills the slot (no stale items after the layout changes)
        for (int row : WORK_ROWS) {
            for (int col = 0; col < 7; col++) {
                put(row + col, null);
            }
        }

        LostCaravanSession session = manager.getCurrentSession();
        LostCaravanLot lot = manager.getCurrentAuctionLot();
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        long timeRem = manager.getCurrentLotTimeRemainingSeconds();
        int lotIdx = lot != null ? (lot.lotIndex() + 1) : 0;
        int totalLots = manager.getActiveLots().size();

        put(SLOT_INFO, GuiUtils.createCustomHead(
                HeadTextures.CARAVAN_LOST_INFO,
                lang().text("gui.lost.auction-info", "lot", String.valueOf(lotIdx), "total", String.valueOf(totalLots)),
                lang().lines("gui.lost.auction-info-lore", "lot", String.valueOf(lotIdx), "total", String.valueOf(totalLots),
                        "players", String.valueOf(session != null ? session.participantCount() : 0),
                        "snipe", String.valueOf(manager.antiSnipeThresholdSeconds()))
        ));

        if (lot != null) {
            renderLot(lot, lotIdx, timeRem, eco);
            renderBidButtons(lot, eco);
            renderBids(lot, eco);
            renderQueue(eco);
        }

        put(SLOT_CLOSE, GuiUtils.createCustomHead(
                HeadTextures.BUTTON_CLOSE,
                lang().text("gui.close"),
                lang().lines("gui.close-lore")
        ));
    }

    private void renderLot(LostCaravanLot lot, int lotIdx, long timeRem, LoveEconomy eco) {
        // Timer card with a progress bar of the lot's bidding window
        put(SLOT_TIMER, GuiUtils.createCustomHead(
                HeadTextures.CARAVAN_LOST_TIMER,
                lang().text("gui.lost.auction-timer", "seconds", String.valueOf(timeRem)),
                lang().lines("gui.lost.auction-timer-lore", "seconds", String.valueOf(timeRem), "bar", timeBar(timeRem),
                        "snipe", String.valueOf(manager.antiSnipeThresholdSeconds()),
                        "extend", String.valueOf(manager.antiSnipeExtendSeconds()))
        ));

        ItemStack preview = lot.crateItem().clone();
        ItemMeta meta = preview.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>();
            String crate = lot.secret() ? lang().text("gui.lost.auction-lot-secret")
                    : lang().text("gui.lost.auction-lot-number", "number", String.valueOf(lotIdx));
            List<String> loreLines = new ArrayList<>(lang().lines("gui.lost.auction-lot-lore", "crate", crate,
                    "start", CoinFormat.formatGlyphs(eco, lot.startingPrice())));
            loreLines.add(lot.currentBid() > 0
                    ? lang().text("gui.lost.auction-bid-current", "bid", CoinFormat.formatGlyphs(eco, lot.currentBid()))
                    : lang().text("gui.lost.auction-bid-none"));
            for (String line : loreLines) lore.add(GuiUtils.line(player, line));
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        put(SLOT_LOT_PREVIEW, preview);
    }

    private void renderBidButtons(LostCaravanLot lot, LoveEconomy eco) {
        boolean leading = player.getUniqueId().equals(lot.highestBidder());

        put(SLOT_MIN_BID, bidButton(HeadTextures.BUTTON_PLUS,
                leading ? "gui.lost.auction-min-bid-off" : "gui.lost.auction-min-bid", "",
                manager.minimumBid(lot), leading, eco));

        int[] slots = {SLOT_BID_1, SLOT_BID_2, SLOT_BID_3};
        String[] colors = {"<green>", "<gold>", "<yellow>"};
        for (int i = 0; i < slots.length; i++) {
            put(slots[i], bidButton(HeadTextures.BUTTON_PLUS,
                    leading ? "gui.lost.auction-quick-bid-off" : "gui.lost.auction-quick-bid", colors[i],
                    quickBid(manager, lot, i), leading, eco, "percent", String.valueOf(quickPercent(i))));
        }

        put(SLOT_CUSTOM_BID, GuiUtils.createCustomHead(
                HeadTextures.COMMISSION_PRICE,
                lang().text("gui.lost.auction-custom"),
                lang().lines("gui.lost.auction-custom-lore")));
    }

    /** A bid button; the leader sees it as blocked (a leading bidder cannot outbid himself). */
    private ItemStack bidButton(String texture, String nameKey, String color, long amount, boolean leading, LoveEconomy eco, String... kv) {
        List<String> nameKv = new ArrayList<>(List.of(kv));
        nameKv.add("color");
        nameKv.add(color);
        String name = lang().text(nameKey, nameKv.toArray(new String[0]));
        List<String> lore = new ArrayList<>(lang().lines(leading ? "gui.lost.auction-bid-off-lore" : "gui.lost.auction-bid-lore",
                "amount", CoinFormat.formatGlyphs(eco, amount)));
        if (leading) {
            lore.addAll(LoreBlocks.blocked(lang().style(), "unavailable", lang().text("gui.lost.auction-bid-leading"),
                    LoreBlocks.hint(LoreBlocks.Click.LEFT, lang().text("gui.lost.auction-bid-click"))));
        }
        return GuiUtils.createCustomHead(texture, name, lore);
    }

    /** One card with the leader and the next bidders; replaces the separate bidder heads. */
    private void renderBids(LostCaravanLot lot, LoveEconomy eco) {
        boolean leading = player.getUniqueId().equals(lot.highestBidder());
        int shown = Math.max(1, plugin.getConfig().getInt("caravan.lost.bids-shown", DEFAULT_BIDS_SHOWN));
        List<CaravanBidBoard.Bid> bids = new ArrayList<>();
        for (LostCaravanManager.BidEntry entry : manager.getRecentBids(lot.id(), 40)) {
            bids.add(new CaravanBidBoard.Bid(entry.bidder(), entry.bidderName(), entry.amount()));
        }
        List<String> lore = new ArrayList<>();
        lore.add(lang().text("gui.lost.auction-bids-subtitle"));
        lore.add("");
        lore.add(lang().text("gui.lost.auction-bids-section"));
        for (String row : CaravanBidBoard.lines(bids, player.getUniqueId(), shown, amount -> CoinFormat.formatGlyphs(eco, amount))) {
            lore.add("  " + row);
        }
        lore.add("");
        lore.add(lang().text(leading ? "gui.lost.auction-bids-ok" : "gui.lost.auction-bids-behind"));
        put(SLOT_BIDS, GuiUtils.createCustomHead(
                leading ? HeadTextures.HEAD_CONFIRM : HeadTextures.BANKER_DEPOSIT_EMPTY,
                lang().text(leading ? "gui.lost.auction-bids-leading" : "gui.lost.auction-bids"),
                lore));
    }

    /** Next lots in the queue (secret crates are shown without hinting what is inside). */
    private void renderQueue(LoveEconomy eco) {
        List<LostCaravanLot> upcoming = manager.getUpcomingLots(SLOTS_QUEUE.length);
        for (int i = 0; i < upcoming.size(); i++) {
            LostCaravanLot next = upcoming.get(i);
            put(SLOTS_QUEUE[i], GuiUtils.createCustomHead(
                    next.secret() ? HeadTextures.CARAVAN_LOST_SECRET : HeadTextures.CARAVAN_LOST_CRATE,
                    lang().text(next.secret() ? "gui.lost.auction-queue-secret" : "gui.lost.auction-queue",
                            "number", String.valueOf(next.lotIndex() + 1)),
                    lang().lines("gui.lost.auction-queue-lore", "start", CoinFormat.formatGlyphs(eco, next.startingPrice()))));
        }
    }

    /** 16-cell bar with percent for the share of the bidding window that is left (red when short, then yellow, then green). */
    private String timeBar(long timeRem) {
        long total = Math.max(1, plugin.getConfig().getLong("caravan.lost.bid-duration-seconds", 60));
        double share = (double) Math.max(0, timeRem) / total;
        String color = share <= 0.2 ? "red" : share <= 0.5 ? "yellow" : "green";
        return ProgressBarUtil.bar(Math.max(0, timeRem), total, color);
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
            MessageUtils.event(player, "<red>Аукцион сейчас не активен.</red>");
            player.closeInventory();
            return;
        }

        LostCaravanLot lot = manager.getCurrentAuctionLot();
        if (lot == null) {
            MessageUtils.event(player, "<red>Нет активного лота.</red>");
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
                            case TOO_LOW -> MessageUtils.event(player, "<red>Ставка слишком мала! Она должна превышать текущую.</red>");
                            case NO_MONEY -> MessageUtils.event(player, "<red>У вас недостаточно монет для такой ставки!</red>");
                            case ALREADY_HIGHEST -> MessageUtils.event(player, "<yellow>Вы уже лидируете в торгах!</yellow>");
                            case NOT_REGISTERED -> MessageUtils.event(player, "<red>Вы не вносили залог для участия!</red>");
                            default -> MessageUtils.event(player, "<red>Не удалось сделать ставку.</red>");
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
            case TOO_LOW -> MessageUtils.event(player, "<red>Ставка слишком мала: нужно выше текущей минимум на шаг повышения (по умолчанию 5%).</red>");
            case NO_MONEY -> MessageUtils.event(player, "<red>У вас недостаточно монет для такой ставки!</red>");
            case ALREADY_HIGHEST -> MessageUtils.event(player, "<yellow>Вы уже лидируете в торгах!</yellow>");
            case NOT_REGISTERED -> MessageUtils.event(player, "<red>Вы не вносили залог для участия!</red>");
            case AUCTION_NOT_ACTIVE -> MessageUtils.event(player, "<red>Торги за этот лот уже закончились.</red>");
            case DB_ERROR -> MessageUtils.event(player, "<red>Не удалось сохранить ставку, монеты возвращены.</red>");
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
