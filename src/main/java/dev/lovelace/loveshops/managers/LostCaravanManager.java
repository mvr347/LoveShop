package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.utils.Money;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.LostCaravanAuctionGui;
import dev.lovelace.loveshops.gui.LostCaravanEntryGui;
import dev.lovelace.loveshops.gui.LostCaravanInstantGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.models.caravan.LostCaravanLootEntry;
import dev.lovelace.loveshops.models.caravan.LostCaravanLot;
import dev.lovelace.loveshops.models.caravan.LostCaravanSession;
import dev.lovelace.loveshops.textures.HeadTextures;
import dev.lovelace.loveshops.utils.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Менеджер еженедельного аукциона «Потерянные Караваны» (Lost Caravans):
 * - Расписание появления в окне (например 10:00-20:00)
 * - Предварительное оповещение (10 минут) со спавном NPC lostcaravan
 * - Фаза регистрации со списанием залога (1 железная монета LoveEconomy) и проверкой кулдауна 7 дней
 * - Определение режима: <= 6 игроков -> INSTANT, > 6 игроков -> AUCTION
 * - Добавление 6-го секретного ящика при >= 12 участниках
 * - Поочерёдные раунды ставок (60 сек) с резервированием и возвратом перебитых ставок
 * - Фаза расчёта (settle-minutes): возврат 50% залога неуспешным участникам
 * - Выдача предметов ящиков и их открытие при ПКМ
 */
public class LostCaravanManager {

    public static final String CRATE_TYPE_KEY = "caravan_crate_type";
    public static final String CRATE_SESSION_KEY = "caravan_session_id";

    public enum RegisterResult {
        SUCCESS,
        ALREADY_REGISTERED,
        NO_FEE,
        EVENT_NOT_REGISTRATION,
        DB_ERROR
    }

    public enum BidResult {
        SUCCESS,
        NOT_REGISTERED,
        AUCTION_NOT_ACTIVE,
        TOO_LOW,
        NO_MONEY,
        ALREADY_HIGHEST,
        DB_ERROR
    }

    /** One accepted bid, kept in memory for the auction menu's "recent bids" row. */
    public record BidEntry(int lotId, UUID bidder, String bidderName, int amount, long atMillis) {}

    /** Most recent bids kept in memory (a few are shown in the menu, see caravan.lost.recent-bids-shown). */
    private static final int RECENT_BIDS_KEPT = 40;

    private final Deque<BidEntry> recentBids = new ArrayDeque<>();

    private final LoveShops plugin;
    private final NamespacedKey crateTypeKey;
    private final NamespacedKey crateSessionKey;

    private LostCaravanSession currentSession;
    private final List<LostCaravanLot> activeLots = new ArrayList<>();
    private final List<LostCaravanLootEntry> defaultLootTable = new ArrayList<>();
    private final List<LostCaravanLootEntry> secretLootTable = new ArrayList<>();

    // Состояние текущего лота на аукционе
    private int currentAuctionLotIndex = -1;
    private String pendingForceMode;
    private final Map<UUID, BossBar> participantBossBars = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask bossBarTickTask;
    private long currentLotEndTimestamp = 0;
    private org.bukkit.scheduler.BukkitTask schedulerTask;
    private org.bukkit.scheduler.BukkitTask registrationTask;
    private org.bukkit.scheduler.BukkitTask auctionTickTask;

    public LostCaravanManager(LoveShops plugin) {
        this.plugin = plugin;
        this.crateTypeKey = new NamespacedKey(plugin, CRATE_TYPE_KEY);
        this.crateSessionKey = new NamespacedKey(plugin, CRATE_SESSION_KEY);
        loadLootTables();
        loadActiveSessionFromDb();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("caravan.lost.enabled", true);
    }

    public boolean isEventActive() {
        return currentSession != null && !"CLOSED".equalsIgnoreCase(currentSession.status());
    }

    public boolean isRegistrationPhase() {
        return currentSession != null && "ANNOUNCED".equalsIgnoreCase(currentSession.status());
    }

    public boolean isAuctionPhase() {
        return currentSession != null && "OPEN".equalsIgnoreCase(currentSession.status());
    }

    public boolean isSettlingPhase() {
        return currentSession != null && "SETTLING".equalsIgnoreCase(currentSession.status());
    }

    public @Nullable LostCaravanSession getCurrentSession() {
        return currentSession;
    }

    public List<LostCaravanLot> getActiveLots() {
        return Collections.unmodifiableList(activeLots);
    }

    public @Nullable LostCaravanLot getCurrentAuctionLot() {
        if (currentAuctionLotIndex >= 0 && currentAuctionLotIndex < activeLots.size()) {
            return activeLots.get(currentAuctionLotIndex);
        }
        return null;
    }

    /** Lots queued after the current one, at most {@code max}, in auction order. */
    public synchronized List<LostCaravanLot> getUpcomingLots(int max) {
        List<LostCaravanLot> upcoming = new ArrayList<>();
        for (int i = currentAuctionLotIndex + 1; i < activeLots.size() && upcoming.size() < max; i++) {
            upcoming.add(activeLots.get(i));
        }
        return upcoming;
    }

    /** Newest-first bids placed on the given lot, at most {@code max}. */
    public synchronized List<BidEntry> getRecentBids(int lotId, int max) {
        List<BidEntry> result = new ArrayList<>();
        for (BidEntry entry : recentBids) {
            if (entry.lotId() == lotId) {
                result.add(entry);
                if (result.size() >= max) break;
            }
        }
        return result;
    }

    /** The cheapest bid that would be accepted right now (starting price, or current bid plus the minimum raise). */
    public int minimumBid(LostCaravanLot lot) {
        return lot.currentBid() > 0 ? lot.currentBid() + minRaiseOver(lot.currentBid()) : lot.startingPrice();
    }

    /** Anti-snipe: a bid within this many seconds of the end extends the lot (caravan.lost.anti-snipe.threshold-seconds). */
    public int antiSnipeThresholdSeconds() {
        return Math.max(0, plugin.getConfig().getInt("caravan.lost.anti-snipe.threshold-seconds", 10));
    }

    /** How many seconds an anti-snipe bid adds (caravan.lost.anti-snipe.extend-seconds; 0 turns the extension off). */
    public int antiSnipeExtendSeconds() {
        return Math.max(0, plugin.getConfig().getInt("caravan.lost.anti-snipe.extend-seconds", 15));
    }

    public long getCurrentLotTimeRemainingSeconds() {
        return Math.max(0, currentLotEndTimestamp - (System.currentTimeMillis() / 1000));
    }

    public void start() {
        if (!isEnabled()) return;
        // Проверка расписания каждые 30 секунд. Именно на главном потоке: фазы события выдают предметы и
        // монеты, шлют сообщения и меняют боссбары - всё это Bukkit API, которому нельзя на async-потоке.
        schedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkSchedule, 300L, 600L);
        // Registration is watched every second: the countdown in open menus stays live and the auction starts
        // exactly at 0:00 instead of up to 30 seconds late (the schedule check above runs twice a minute).
        registrationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRegistration, 40L, 20L);
    }

    private void tickRegistration() {
        LostCaravanSession session = currentSession;
        if (session == null || !"ANNOUNCED".equalsIgnoreCase(session.status())) return;
        if (System.currentTimeMillis() / 1000 >= session.openedAt()) {
            openSession();
        } else {
            LostCaravanEntryGui.refreshAll();
        }
    }

    /** Runs on the main thread: open inventories must not be touched from anywhere else. */
    private void onMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Registration is over: participants' registration menus turn into the auction/shop window, others are closed. */
    private void switchRegistrationMenus() {
        onMainThread(() -> {
            LostCaravanSession session = currentSession;
            if (session == null) return;
            boolean auction = "AUCTION".equalsIgnoreCase(session.mode());
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!(p.getOpenInventory().getTopInventory().getHolder() instanceof LostCaravanEntryGui)) continue;
                if (!isParticipant(session.id(), p.getUniqueId())) {
                    p.closeInventory();
                    MessageUtils.sendMessage(p, "<gray>Регистрация закончилась: меню лотов доступно только участникам.</gray>");
                } else if (auction) {
                    new LostCaravanAuctionGui(plugin, p, this).open();
                } else {
                    new LostCaravanInstantGui(plugin, p, this).open();
                }
            }
        });
    }

    /** The event is over: close every open window of the given kinds. */
    private void closeMenus(boolean includeShop) {
        onMainThread(() -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                var holder = p.getOpenInventory().getTopInventory().getHolder();
                if (holder instanceof LostCaravanAuctionGui
                        || (includeShop && (holder instanceof LostCaravanInstantGui || holder instanceof LostCaravanEntryGui))) {
                    p.closeInventory();
                }
            }
        });
    }

    public synchronized void startAuctionTicker() {
        if (auctionTickTask != null && !auctionTickTask.isCancelled()) return;
        auctionTickTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (isAuctionPhase()) {
                tickAuction();
                LostCaravanAuctionGui.refreshAll(plugin);
            } else {
                stopAuctionTicker();
            }
        }, 20L, 20L);
    }

    public synchronized void stopAuctionTicker() {
        if (auctionTickTask != null) {
            auctionTickTask.cancel();
            auctionTickTask = null;
        }
    }

    public void stop() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
            schedulerTask = null;
        }
        if (registrationTask != null) {
            registrationTask.cancel();
            registrationTask = null;
        }
        stopAuctionTicker();
        clearAllParticipantBossBars();
    }

    public void reload() {
        loadLootTables();
    }

    private void loadLootTables() {
        defaultLootTable.clear();
        secretLootTable.clear();

        ConfigurationSection defSec = plugin.getConfig().getConfigurationSection("caravan.lost.crates.default");
        if (defSec != null) {
            for (String key : defSec.getKeys(false)) {
                ConfigurationSection itemSec = defSec.getConfigurationSection(key);
                if (itemSec != null) {
                    String mat = itemSec.getString("material", itemSec.getString("itemsadder", "IRON_INGOT"));
                    double chance = itemSec.getDouble("chance", 50.0);
                    int min = itemSec.getInt("min", 1);
                    int max = itemSec.getInt("max", 1);
                    defaultLootTable.add(new LostCaravanLootEntry(mat, chance, min, max));
                }
            }
        }

        ConfigurationSection secSec = plugin.getConfig().getConfigurationSection("caravan.lost.crates.secret");
        if (secSec != null) {
            for (String key : secSec.getKeys(false)) {
                ConfigurationSection itemSec = secSec.getConfigurationSection(key);
                if (itemSec != null) {
                    String mat = itemSec.getString("material", itemSec.getString("itemsadder", "DIAMOND"));
                    double chance = itemSec.getDouble("chance", 50.0);
                    int min = itemSec.getInt("min", 1);
                    int max = itemSec.getInt("max", 1);
                    secretLootTable.add(new LostCaravanLootEntry(mat, chance, min, max));
                }
            }
        }
    }

    private void checkSchedule() {
        long now = System.currentTimeMillis() / 1000;

        if (currentSession != null) {
            if ("ANNOUNCED".equalsIgnoreCase(currentSession.status())) {
                if (now >= currentSession.openedAt()) {
                    openSession();
                }
            } else if ("OPEN".equalsIgnoreCase(currentSession.status())) {
                if ("AUCTION".equalsIgnoreCase(currentSession.mode())) {
                    tickAuction();
                }
            } else if ("SETTLING".equalsIgnoreCase(currentSession.status())) {
                if (now >= currentSession.closedAt()) {
                    closeSession();
                }
            }
            return;
        }

        // Проверяем, наступило ли время создания новой сессии
        // Для еженедельного расписания ищем последнюю закрытую сессию
        long lastSessionTime = getLastSessionScheduledTime();
        long sevenDays = 7L * 86400L;

        if (now - lastSessionTime >= sevenDays) {
            // Проверяем попадание в окно spawn-window
            String startStr = plugin.getConfig().getString("caravan.lost.spawn-window-start", "10:00");
            String endStr = plugin.getConfig().getString("caravan.lost.spawn-window-end", "20:00");
            try {
                LocalTime start = LocalTime.parse(startStr);
                LocalTime end = LocalTime.parse(endStr);
                LocalTime current = LocalTime.now();
                if (!current.isBefore(start) && !current.isAfter(end)) {
                    // Стартуем сессию с 10-минутным анонсом
                    announceSession(now);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка разбора spawn-window для Lost Caravans: " + e.getMessage());
            }
        }
    }

    public synchronized void announceSession(long scheduledAt) {
        int announceMinutes = plugin.getConfig().getInt("caravan.lost.announce-minutes", 10);
        long openAt = scheduledAt + (announceMinutes * 60L);
        long settleMinutes = plugin.getConfig().getInt("caravan.lost.settle-minutes", 15);
        long closeAt = openAt + (30 * 60L) + (settleMinutes * 60L); // примерная длительность

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO lost_caravan_sessions (scheduled_at, opened_at, closed_at, status, mode, participant_count, secret_crate)
                VALUES (?, ?, ?, 'ANNOUNCED', 'AUCTION', 0, 0)
            """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, scheduledAt);
            ps.setLong(2, openAt);
            ps.setLong(3, closeAt);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) {
                int id = rs.getInt(1);
                currentSession = new LostCaravanSession(id, scheduledAt, openAt, closeAt, "ANNOUNCED", "AUCTION", 0, false, scheduledAt);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка создания сессии Lost Caravan: " + e.getMessage());
            return;
        }

        // Спавним NPC
        Bukkit.getScheduler().runTask(plugin, () -> {
            spawnCaravanNpc();
        });

        // Оповещение и эффекты
        CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <yellow>В окрестностях обнаружен потерянный караван! Регистрация открыта на "
                + announceMinutes + " мин. Внесите залог у торговца каравана.</yellow>");
    }

    public synchronized void openSession() {
        if (currentSession == null) return;
        int participantCount = getParticipantCount(currentSession.id());
        if (participantCount <= 0 && plugin.getConfig().getBoolean("caravan.lost.leave-if-empty", true)) {
            CaravanEffects.broadcast("<gold>Потерянный караван</gold> <gray>— никто не внёс залог. Обоз уезжает.</gray>");
            closeSession();
            return;
        }
        int minForAuction = plugin.getConfig().getInt("caravan.lost.min-players-for-auction", 6);
        boolean secretCrate = participantCount >= plugin.getConfig().getInt("caravan.lost.secret-crate-chance-players", 12);
        String mode = participantCount <= minForAuction ? "INSTANT" : "AUCTION";
        if (pendingForceMode != null) {
            String fm = pendingForceMode;
            pendingForceMode = null;
            if ("AUCTION".equals(fm)) {
                mode = "AUCTION";
            } else if ("SECRET".equals(fm)) {
                mode = participantCount <= minForAuction ? "INSTANT" : "AUCTION";
                secretCrate = true;
            } else if ("BASE".equals(fm) || "INSTANT".equals(fm)) {
                mode = "INSTANT";
            }
        }

        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                UPDATE lost_caravan_sessions
                SET status = 'OPEN', mode = ?, participant_count = ?, secret_crate = ?, opened_at = ?
                WHERE id = ?
            """);
            ps.setString(1, mode);
            ps.setInt(2, participantCount);
            ps.setInt(3, secretCrate ? 1 : 0);
            ps.setLong(4, now);
            ps.setInt(5, currentSession.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка открытия сессии Lost Caravan: " + e.getMessage());
        }

        currentSession = new LostCaravanSession(
                currentSession.id(),
                currentSession.scheduledAt(),
                now,
                currentSession.closedAt(),
                "OPEN",
                mode,
                participantCount,
                secretCrate,
                currentSession.createdAt()
        );

        // Создание лотов
        createLots(currentSession.id(), secretCrate);

        if ("INSTANT".equalsIgnoreCase(mode)) {
            CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <yellow>Участников мало ("
                    + participantCount + " чел.)! Караван переходит в режим МГНОВЕННОЙ ПОКУПКИ ящиков!</yellow>");
        } else {
            CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <yellow>Начинается аукцион ящиков каравана! Всего лотов: "
                    + activeLots.size() + (secretCrate ? " (включая Секретный Ящик!)" : "") + ". Торги открыты!</yellow>");
            currentAuctionLotIndex = 0;
            startLotRound(0);
            startAuctionTicker();
        }
        switchRegistrationMenus();
    }

    /**
     * Starting price of a lot. 2026-10-03: {@code caravan.lost.starting-price} (default "8i" = 800) and
     * {@code secret-starting-price} ("25i") are money values - the loot of a crate is worth about that: ~3 rolls of
     * ores/ingots (diamonds 3-8, gold 12-32, scrap ...), the secret one netherite, diamond blocks and totems.
     */
    int startingPrice(boolean secret) {
        long value = secret
                ? Money.scaled(plugin.getConfig(), "caravan.lost.secret-starting-price", 2_500L)
                : Money.scaled(plugin.getConfig(), "caravan.lost.starting-price", 800L);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, value));
    }

    /** Deposit for taking part ({@code caravan.lost.entry-fee.amount}: number or money text, default "1i" = 100). */
    public int entryFee() {
        long fee = Money.scaled(plugin.getConfig(), "caravan.lost.entry-fee.amount", 100L);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, fee));
    }

    /** Smallest raise over the current bid, percent ({@code caravan.lost.min-raise-percent}, default 5). */
    public int minRaiseOver(int currentBid) {
        double percent = Math.max(0.0, plugin.getConfig().getDouble("caravan.lost.min-raise-percent", 5.0));
        return Math.max(1, (int) Math.round(currentBid * percent / 100.0));
    }

    private void createLots(int sessionId, boolean secretCrate) {
        activeLots.clear();
        int baseCratesCount = plugin.getConfig().getInt("caravan.lost.crates-count", 5);
        int totalCrates = secretCrate ? baseCratesCount + 1 : baseCratesCount;
        int startingPriceBase = startingPrice(false);
        int startingPriceSecret = startingPrice(true);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            for (int i = 0; i < totalCrates; i++) {
                boolean isSecret = secretCrate && (i == totalCrates - 1);
                ItemStack crateItem = createCrateItem(isSecret, sessionId);
                String base64 = ItemStackConverter.itemStackToBase64(crateItem);

                PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO lost_caravan_lots (session_id, lot_index, is_secret, crate_item_data, starting_price, current_bid, status)
                    VALUES (?, ?, ?, ?, ?, 0, 'PENDING')
                """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, sessionId);
                ps.setInt(2, i);
                ps.setInt(3, isSecret ? 1 : 0);
                ps.setString(4, base64);
                // each crate gets its own price within +-price-variance-percent of the base; the contents do not matter
                int startingPrice = PriceJitter.apply(isSecret ? startingPriceSecret : startingPriceBase,
                        plugin.getConfig().getInt("caravan.lost.price-variance-percent", 25),
                        ThreadLocalRandom.current().nextDouble());
                ps.setInt(5, startingPrice);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int lotId = rs.getInt(1);
                    activeLots.add(new LostCaravanLot(
                            lotId, sessionId, i, isSecret, crateItem, startingPrice, 0, null, "PENDING", 0, 0
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка создания лотов Lost Caravan: " + e.getMessage());
        }
    }

    private void startLotRound(int lotIndex) {
        if (lotIndex < 0 || lotIndex >= activeLots.size()) {
            finishAllLots();
            return;
        }

        LostCaravanLot lot = activeLots.get(lotIndex);
        int durationSec = plugin.getConfig().getInt("caravan.lost.bid-duration-seconds", 60);
        long now = System.currentTimeMillis() / 1000;
        currentLotEndTimestamp = now + durationSec;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                UPDATE lost_caravan_lots SET status = 'ACTIVE', started_at = ? WHERE id = ?
            """);
            ps.setLong(1, now);
            ps.setInt(2, lot.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка старта лота: " + e.getMessage());
        }

        activeLots.set(lotIndex, new LostCaravanLot(
                lot.id(), lot.sessionId(), lot.lotIndex(), lot.secret(), lot.crateItem(),
                lot.startingPrice(), lot.currentBid(), lot.highestBidder(), "ACTIVE", now, 0
        ));

        String crateName = lot.secret() ? "<red>⚡ СЕКРЕТНЫЙ ЯЩИК</red>" : "<gold>Ящик #" + (lotIndex + 1) + "</gold>";
        CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <yellow>Открыты торги за "
                + crateName + "! Начальная ставка: " + CoinFormat.formatGlyphs(lot.startingPrice()) + ". Время: " + durationSec + " сек.</yellow>");
    }

    private void tickAuction() {
        if (currentAuctionLotIndex < 0 || currentAuctionLotIndex >= activeLots.size()) return;
        long now = System.currentTimeMillis() / 1000;

        if (now >= currentLotEndTimestamp) {
            // Раунд завершён
            endCurrentLotRound();
        }
    }

    private void endCurrentLotRound() {
        if (currentAuctionLotIndex < 0 || currentAuctionLotIndex >= activeLots.size()) return;
        LostCaravanLot lot = activeLots.get(currentAuctionLotIndex);
        long now = System.currentTimeMillis() / 1000;

        if (lot.highestBidder() != null && lot.currentBid() > 0) {
            // Лот продан победителю
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                PreparedStatement ps = conn.prepareStatement("""
                    UPDATE lost_caravan_lots SET status = 'SOLD', ended_at = ? WHERE id = ?
                """);
                ps.setLong(1, now);
                ps.setInt(2, lot.id());
                ps.executeUpdate();

                PreparedStatement psPart = conn.prepareStatement("""
                    UPDATE lost_caravan_participants SET won_crates = won_crates + 1
                    WHERE session_id = ? AND player_uuid = ?
                """);
                psPart.setInt(1, lot.sessionId());
                psPart.setString(2, lot.highestBidder().toString());
                psPart.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка завершения раунда лота: " + e.getMessage());
            }

            activeLots.set(currentAuctionLotIndex, new LostCaravanLot(
                    lot.id(), lot.sessionId(), lot.lotIndex(), lot.secret(), lot.crateItem(),
                    lot.startingPrice(), lot.currentBid(), lot.highestBidder(), "SOLD", lot.startedAt(), now
            ));

            Player winner = Bukkit.getPlayer(lot.highestBidder());
            if (winner != null && winner.isOnline()) {
                giveOrDropItem(winner, lot.crateItem().clone());
                CaravanEffects.playSubmitEffects(winner);
                MessageUtils.sendMessage(winner, "<green>Поздравляем! Вы выиграли "
                        + (lot.secret() ? "Секретный Ящик" : "Ящик каравана") + " со ставкой " + CoinFormat.formatGlyphs(lot.currentBid()) + "!</green>");
            } else {
                // Если победитель офлайн, сохраняем в pending_returns
                savePendingCrate(lot.highestBidder(), lot.crateItem().clone());
            }

            String winnerName = Bukkit.getOfflinePlayer(lot.highestBidder()).getName();
            CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <green>"
                    + (lot.secret() ? "⚡ Секретный Ящик" : "Ящик #" + (lot.lotIndex() + 1))
                    + " продан игроку <gold>" + winnerName + "</gold> за " + lot.currentBid() + " монет!</green>");
        } else {
            // Ставок не было
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                PreparedStatement ps = conn.prepareStatement("""
                    UPDATE lost_caravan_lots SET status = 'EXPIRED', ended_at = ? WHERE id = ?
                """);
                ps.setLong(1, now);
                ps.setInt(2, lot.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка отметки EXPIRED: " + e.getMessage());
            }

            activeLots.set(currentAuctionLotIndex, new LostCaravanLot(
                    lot.id(), lot.sessionId(), lot.lotIndex(), lot.secret(), lot.crateItem(),
                    lot.startingPrice(), lot.currentBid(), null, "EXPIRED", lot.startedAt(), now
            ));

            CaravanEffects.broadcast("<gray>[Потерянный Караван] На лот #" + (lot.lotIndex() + 1) + " не поступило ставок.</gray>");
        }

        // Переход к следующему лоту
        currentAuctionLotIndex++;
        if (currentAuctionLotIndex < activeLots.size()) {
            startLotRound(currentAuctionLotIndex);
        } else {
            finishAllLots();
        }
    }

    private void finishAllLots() {
        stopAuctionTicker();
        if (currentSession == null) return;
        int settleMinutes = plugin.getConfig().getInt("caravan.lost.settle-minutes", 15);
        long now = System.currentTimeMillis() / 1000;
        long settleEnd = now + (settleMinutes * 60L);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                UPDATE lost_caravan_sessions SET status = 'SETTLING', closed_at = ? WHERE id = ?
            """);
            ps.setLong(1, settleEnd);
            ps.setInt(2, currentSession.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка перевода в SETTLING: " + e.getMessage());
        }

        currentSession = new LostCaravanSession(
                currentSession.id(), currentSession.scheduledAt(), currentSession.openedAt(),
                settleEnd, "SETTLING", currentSession.mode(), currentSession.participantCount(),
                currentSession.secretCrate(), currentSession.createdAt()
        );

        // Возврат 50% залога неуспешным участникам
        refundNonWinners(currentSession.id());

        closeMenus(false);
        CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <yellow>Все торги завершены! Караван простоит ещё "
                + settleMinutes + " мин. для расчётов.</yellow>");
    }

    /** Coins back to a player: straight into the inventory when they fit, otherwise into the pending payouts. */
    private void returnMoney(UUID uuid, @Nullable Player online, int amount, LoveEconomy eco) {
        if (amount <= 0) return;
        if (online != null && online.isOnline() && eco != null && eco.canFit(online, amount)) {
            eco.give(online, amount);
        } else {
            addPendingRefund(uuid, amount);
        }
    }

    /** A session closed (stop, timeout) while a lot still has a leader: their charged bid goes back. */
    private void refundStandingBid() {
        if (currentAuctionLotIndex < 0 || currentAuctionLotIndex >= activeLots.size()) return;
        LostCaravanLot lot = activeLots.get(currentAuctionLotIndex);
        if (!"ACTIVE".equalsIgnoreCase(lot.status()) || lot.highestBidder() == null || lot.currentBid() <= 0) return;
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        Player leader = Bukkit.getPlayer(lot.highestBidder());
        returnMoney(lot.highestBidder(), leader, lot.currentBid(), eco);
        if (leader != null && leader.isOnline()) {
            MessageUtils.sendMessage(leader, "<yellow>[Потерянный Караван] Торги прерваны, ваша ставка возвращена.</yellow>");
        }
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE lost_caravan_lots SET status = 'EXPIRED', ended_at = strftime('%s','now') WHERE id = ?");
            ps.setInt(1, lot.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Не удалось закрыть лот после возврата ставки: " + e.getMessage());
        }
    }

    public synchronized void closeSession() {
        clearAllParticipantBossBars();
        stopAuctionTicker();
        if (currentSession == null) return;
        refundStandingBid();
        long now = System.currentTimeMillis() / 1000;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                UPDATE lost_caravan_sessions SET status = 'CLOSED', closed_at = ? WHERE id = ?
            """);
            ps.setLong(1, now);
            ps.setInt(2, currentSession.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка закрытия сессии: " + e.getMessage());
        }

        currentSession = null;
        activeLots.clear();
        currentAuctionLotIndex = -1;
        closeMenus(true);

        Bukkit.getScheduler().runTask(plugin, () -> {
            despawnCaravanNpc();
        });

        CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <gray>Караван свернул лагерь и отправился в дальний путь. До следующей недели!</gray>");
    }

    /**
     * Ставка игрока в режиме аукциона.
     */
    public synchronized BidResult placeBid(Player player, int amount) {
        if (!isAuctionPhase() || currentSession == null) return BidResult.AUCTION_NOT_ACTIVE;
        LostCaravanLot lot = getCurrentAuctionLot();
        if (lot == null || !"ACTIVE".equalsIgnoreCase(lot.status())) return BidResult.AUCTION_NOT_ACTIVE;

        if (!isParticipant(currentSession.id(), player.getUniqueId())) {
            return BidResult.NOT_REGISTERED;
        }

        if (player.getUniqueId().equals(lot.highestBidder())) {
            return BidResult.ALREADY_HIGHEST;
        }

        int minBid = minimumBid(lot);
        if (amount < minBid) {
            return BidResult.TOO_LOW;
        }

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null || !eco.has(player, amount)) {
            return BidResult.NO_MONEY;
        }

        // 1. Списание ставки у нового лидера
        if (!eco.charge(player, amount)) {
            return BidResult.NO_MONEY;
        }

        // 2. Запись в БД ДО возврата предыдущему лидеру: при сбое новый игрок получает деньги назад,
        // а предыдущий остаётся лидером (раньше монеты исчезали у обоих).
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement psLot = conn.prepareStatement("""
                UPDATE lost_caravan_lots SET current_bid = ?, highest_bidder = ? WHERE id = ?
            """);
            psLot.setInt(1, amount);
            psLot.setString(2, player.getUniqueId().toString());
            psLot.setInt(3, lot.id());
            psLot.executeUpdate();

            PreparedStatement psBid = conn.prepareStatement("""
                INSERT INTO lost_caravan_bids (lot_id, bidder_uuid, amount) VALUES (?, ?, ?)
            """);
            psBid.setInt(1, lot.id());
            psBid.setString(2, player.getUniqueId().toString());
            psBid.setInt(3, amount);
            psBid.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка сохранения ставки: " + e.getMessage());
            returnMoney(player.getUniqueId(), player, amount, eco);
            return BidResult.DB_ERROR;
        }

        // 3. Возврат ставки предыдущему лидеру
        if (lot.highestBidder() != null && lot.currentBid() > 0) {
            Player prevBidder = Bukkit.getPlayer(lot.highestBidder());
            if (prevBidder != null && prevBidder.isOnline()) {
                if (eco.canFit(prevBidder, lot.currentBid())) {
                    eco.give(prevBidder, lot.currentBid());
                    MessageUtils.sendMessage(prevBidder, "<yellow>[Потерянный Караван] Ваша ставка на ящик была перебита! Вам возвращено "
                            + CoinFormat.formatGlyphs(eco, lot.currentBid()) + ".</yellow>");
                } else {
                    addPendingRefund(lot.highestBidder(), lot.currentBid());
                    MessageUtils.sendMessage(prevBidder, "<yellow>[Потерянный Караван] Ваша ставка на ящик была перебита! Ваш инвентарь полон, возврат "
                            + CoinFormat.formatGlyphs(eco, lot.currentBid()) + " сохранён и ожидает вас у торговца.</yellow>");
                }
            } else {
                addPendingRefund(lot.highestBidder(), lot.currentBid());
            }
        }

        // 4. Обновление в памяти
        activeLots.set(currentAuctionLotIndex, new LostCaravanLot(
                lot.id(), lot.sessionId(), lot.lotIndex(), lot.secret(), lot.crateItem(),
                lot.startingPrice(), amount, player.getUniqueId(), "ACTIVE", lot.startedAt(), lot.endedAt()
        ));

        recentBids.addFirst(new BidEntry(lot.id(), player.getUniqueId(), player.getName(), amount, System.currentTimeMillis()));
        while (recentBids.size() > RECENT_BIDS_KEPT) {
            recentBids.removeLast();
        }

        // Анти-снайп: ставка в последние threshold-seconds секунд продлевает лот на extend-seconds
        // (раньше 10 / 15 были зашиты в код; 0 в extend-seconds отключает продление).
        long remaining = currentLotEndTimestamp - (System.currentTimeMillis() / 1000);
        int extend = antiSnipeExtendSeconds();
        if (extend > 0 && remaining < antiSnipeThresholdSeconds()) {
            currentLotEndTimestamp += extend;
            CaravanEffects.broadcast("<yellow>[Анти-снайп] Торги за ящик продлены на " + extend + " сек.!</yellow>");
        }

        LostCaravanAuctionGui.refreshAll(plugin);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f, 1.5f);
        MessageUtils.sendMessage(player, "<green>Вы сделали ставку " + CoinFormat.formatGlyphs(eco, amount) + "! Вы лидируете в торгах.</green>");
        return BidResult.SUCCESS;
    }

    /**
     * Покупка ящика в режиме INSTANT.
     */
    public synchronized boolean buyInstantCrate(Player player, int lotIndex) {
        if (currentSession == null || !"OPEN".equalsIgnoreCase(currentSession.status()) || !"INSTANT".equalsIgnoreCase(currentSession.mode())) {
            return false;
        }
        if (lotIndex < 0 || lotIndex >= activeLots.size()) return false;
        LostCaravanLot lot = activeLots.get(lotIndex);
        if (!"PENDING".equalsIgnoreCase(lot.status()) && !"ACTIVE".equalsIgnoreCase(lot.status())) {
            return false;
        }

        if (!isParticipant(currentSession.id(), player.getUniqueId())) {
            MessageUtils.sendMessage(player, "<red>Вы не вносили залог для участия в этом караване!</red>");
            return false;
        }

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null || !eco.has(player, lot.startingPrice())) {
            MessageUtils.sendMessage(player, "<red>У вас недостаточно монет для покупки этого ящика!</red>");
            return false;
        }

        if (player.getInventory().firstEmpty() == -1) {
            MessageUtils.sendMessage(player, "<red>В вашем инвентаре нет свободного места!</red>");
            return false;
        }

        // Атомарное обновление в БД
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                UPDATE lost_caravan_lots SET status = 'SOLD', current_bid = ?, highest_bidder = ?, ended_at = strftime('%s','now')
                WHERE id = ? AND status IN ('PENDING', 'ACTIVE')
            """);
            ps.setInt(1, lot.startingPrice());
            ps.setString(2, player.getUniqueId().toString());
            ps.setInt(3, lot.id());
            int updated = ps.executeUpdate();
            if (updated == 0) return false;

            PreparedStatement psPart = conn.prepareStatement("""
                UPDATE lost_caravan_participants SET won_crates = won_crates + 1
                WHERE session_id = ? AND player_uuid = ?
            """);
            psPart.setInt(1, currentSession.id());
            psPart.setString(2, player.getUniqueId().toString());
            psPart.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка покупки инстант-ящика: " + e.getMessage());
            return false;
        }

        if (!eco.charge(player, lot.startingPrice())) {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                PreparedStatement psRev = conn.prepareStatement("UPDATE lost_caravan_lots SET status = 'PENDING', current_bid = 0, highest_bidder = NULL WHERE id = ?");
                psRev.setInt(1, lot.id());
                psRev.executeUpdate();
                PreparedStatement psPartRev = conn.prepareStatement("UPDATE lost_caravan_participants SET won_crates = MAX(0, won_crates - 1) WHERE session_id = ? AND player_uuid = ?");
                psPartRev.setInt(1, currentSession.id());
                psPartRev.setString(2, player.getUniqueId().toString());
                psPartRev.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Откат покупки ящика #" + lot.id() + " не удался (лот остался проданным без оплаты): " + e.getMessage());
            }
            MessageUtils.sendMessage(player, "<red>Не удалось списать монеты!</red>");
            return false;
        }

        giveOrDropItem(player, lot.crateItem().clone());
        CaravanEffects.playSubmitEffects(player);

        activeLots.set(lotIndex, new LostCaravanLot(
                lot.id(), lot.sessionId(), lot.lotIndex(), lot.secret(), lot.crateItem(),
                lot.startingPrice(), lot.startingPrice(), player.getUniqueId(), "SOLD", lot.startedAt(), System.currentTimeMillis() / 1000
        ));

        LostCaravanInstantGui.refreshAll(plugin);

        MessageUtils.sendMessage(player, "<green>Вы успешно приобрели "
                + (lot.secret() ? "Секретный Ящик" : "Ящик каравана") + " за " + CoinFormat.formatGlyphs(eco, lot.startingPrice()) + "!</green>");

        // Проверяем, все ли ящики раскуплены
        boolean allSold = activeLots.stream().allMatch(l -> "SOLD".equalsIgnoreCase(l.status()));
        if (allSold) {
            finishAllLots();
        }
        return true;
    }

    /**
     * Регистрация игрока и внесение залога (1 железная монета).
     */
    public synchronized RegisterResult registerParticipant(Player player) {
        if (currentSession == null || !"ANNOUNCED".equalsIgnoreCase(currentSession.status())) {
            return RegisterResult.EVENT_NOT_REGISTRATION;
        }

        UUID uuid = player.getUniqueId();
        if (isParticipant(currentSession.id(), uuid)) {
            return RegisterResult.ALREADY_REGISTERED;
        }

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        int entryFee = entryFee();
        if (eco == null || !eco.has(player, entryFee)) {
            return RegisterResult.NO_FEE;
        }

        // Списание залога
        if (!eco.charge(player, entryFee)) {
            return RegisterResult.NO_FEE;
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO lost_caravan_participants (session_id, player_uuid, entry_fee_paid, refunded, won_crates)
                VALUES (?, ?, ?, 0, 0)
            """);
            ps.setInt(1, currentSession.id());
            ps.setString(2, uuid.toString());
            ps.setInt(3, entryFee);
            ps.executeUpdate();

            PreparedStatement psCount = conn.prepareStatement("""
                UPDATE lost_caravan_sessions SET participant_count = participant_count + 1 WHERE id = ?
            """);
            psCount.setInt(1, currentSession.id());
            psCount.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка регистрации участника Lost Caravan: " + e.getMessage());
            // The deposit is already taken: it must not vanish with the failed registration.
            if (eco.canFit(player, entryFee)) eco.give(player, entryFee);
            else addPendingRefund(uuid, entryFee);
            return RegisterResult.DB_ERROR;
        }

        attachParticipantBossBar(player);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        MessageUtils.sendMessage(player, "<green>Вы успешно зарегистрировались в Потерянном Караване!</green>");
        MessageUtils.sendMessage(player, "<gray>Залог в размере " + CoinFormat.formatGlyphs(eco, entryFee) + " внесён. Ожидайте начала торгов!</gray>");
        return RegisterResult.SUCCESS;
    }

    private void refundNonWinners(int sessionId) {
        int refundPercent = plugin.getConfig().getInt("caravan.lost.entry-fee.refund-percent-if-no-win", 50);
        LoveEconomy eco = plugin.getEconomy().orElse(null);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT player_uuid, entry_fee_paid FROM lost_caravan_participants
                WHERE session_id = ? AND won_crates = 0 AND refunded = 0
            """);
            ps.setInt(1, sessionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                int paid = rs.getInt("entry_fee_paid");
                int refundAmount = Math.max(1, (int) Math.round(paid * (refundPercent / 100.0)));

                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline() && eco != null) {
                    if (eco.canFit(p, refundAmount)) {
                        eco.give(p, refundAmount);
                        MessageUtils.sendMessage(p, "<yellow>[Потерянный Караван] Вам возвращено " + refundPercent + "% залога: "
                                + CoinFormat.formatGlyphs(eco, refundAmount) + ".</yellow>");
                    } else {
                        addPendingRefund(uuid, refundAmount);
                        MessageUtils.sendMessage(p, "<yellow>[Потерянный Караван] Вам возвращено " + refundPercent + "% залога ("
                                + CoinFormat.formatGlyphs(eco, refundAmount) + "), но ваш инвентарь полон! Выплата сохранена и ожидает вас у торговца.</yellow>");
                    }
                } else {
                    addPendingRefund(uuid, refundAmount);
                }

                PreparedStatement psUpd = conn.prepareStatement("""
                    UPDATE lost_caravan_participants SET refunded = 1 WHERE session_id = ? AND player_uuid = ?
                """);
                psUpd.setInt(1, sessionId);
                psUpd.setString(2, uuid.toString());
                psUpd.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка возврата залога: " + e.getMessage());
        }
    }

    private void addPendingRefund(UUID uuid, int amount) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO commission_pending_payouts (seller_uuid, amount) VALUES (?, ?)
            """);
            ps.setString(1, uuid.toString());
            ps.setInt(2, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения pending refund: " + e.getMessage());
        }
    }

    private void savePendingCrate(UUID uuid, ItemStack crate) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO pending_returns (player_uuid, item_data, amount, reason, created_at)
                VALUES (?, ?, 1, 'LOST_CARAVAN_CRATE', strftime('%s','now'))
            """);
            ps.setString(1, uuid.toString());
            ps.setString(2, ItemStackConverter.itemStackToBase64(crate));
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка сохранения pending crate: " + e.getMessage());
        }
    }

    public boolean isParticipant(int sessionId, UUID uuid) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM lost_caravan_participants WHERE session_id = ? AND player_uuid = ?
            """);
            ps.setInt(1, sessionId);
            ps.setString(2, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки участника: " + e.getMessage());
        }
        return false;
    }

    public int getParticipantCount(int sessionId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM lost_caravan_participants WHERE session_id = ?");
            ps.setInt(1, sessionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка подсчёта участников: " + e.getMessage());
        }
        return 0;
    }

    private long getLastSessionScheduledTime() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT scheduled_at FROM lost_caravan_sessions ORDER BY id DESC LIMIT 1");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("scheduled_at");
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка проверки последней сессии: " + e.getMessage());
        }
        return 0;
    }

    private void loadActiveSessionFromDb() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT id, scheduled_at, opened_at, closed_at, status, mode, participant_count, secret_crate, created_at
                FROM lost_caravan_sessions
                WHERE status IN ('ANNOUNCED', 'OPEN', 'SETTLING')
                ORDER BY id DESC LIMIT 1
            """);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                currentSession = new LostCaravanSession(
                        rs.getInt("id"),
                        rs.getLong("scheduled_at"),
                        rs.getLong("opened_at"),
                        rs.getLong("closed_at"),
                        rs.getString("status"),
                        rs.getString("mode"),
                        rs.getInt("participant_count"),
                        rs.getInt("secret_crate") == 1,
                        rs.getLong("created_at")
                );

                // Загружаем лоты
                loadLotsFromDb(currentSession.id());

                if ("OPEN".equalsIgnoreCase(currentSession.status()) && "AUCTION".equalsIgnoreCase(currentSession.mode())) {
                    startAuctionTicker();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки активной сессии Lost Caravan: " + e.getMessage());
        }
    }

    private void loadLotsFromDb(int sessionId) {
        activeLots.clear();
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT id, session_id, lot_index, is_secret, crate_item_data, starting_price, current_bid, highest_bidder, status, started_at, ended_at
                FROM lost_caravan_lots WHERE session_id = ? ORDER BY lot_index ASC
            """);
            ps.setInt(1, sessionId);
            ResultSet rs = ps.executeQuery();
            int idx = 0;
            while (rs.next()) {
                ItemStack item = ItemStackConverter.itemStackFromBase64(rs.getString("crate_item_data"));
                String bidderStr = rs.getString("highest_bidder");
                UUID bidder = bidderStr != null ? UUID.fromString(bidderStr) : null;
                String status = rs.getString("status");
                if ("ACTIVE".equalsIgnoreCase(status)) {
                    currentAuctionLotIndex = idx;
                }
                activeLots.add(new LostCaravanLot(
                        rs.getInt("id"),
                        rs.getInt("session_id"),
                        rs.getInt("lot_index"),
                        rs.getInt("is_secret") == 1,
                        item,
                        rs.getInt("starting_price"),
                        rs.getInt("current_bid"),
                        bidder,
                        status,
                        rs.getLong("started_at"),
                        rs.getLong("ended_at")
                ));
                idx++;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки лотов Lost Caravan: " + e.getMessage());
        }
    }

    /**
     * Создание предмета ящика каравана.
     */
    public ItemStack createCrateItem(boolean secret, int sessionId) {
        String texture = secret ? dev.lovelace.loveshops.textures.HeadTextures.CARAVAN_LOST_SECRET
                : dev.lovelace.loveshops.textures.HeadTextures.CARAVAN_LOST_CRATE;
        Component name = secret
                ? MessageUtils.parse("<gradient:#FF55FF:#FFAA00><bold>⚡ Секретный Ящик Каравана</bold></gradient>")
                : MessageUtils.parse("<gradient:#FFAA00:#FF5555><bold>📦 Ящик Потерянного Каравана</bold></gradient>");
        List<Component> lore = secret ? List.of(
                Component.empty(),
                MessageUtils.parse("<gray>Редчайший запечатанный ящик из глубин каравана.</gray>"),
                MessageUtils.parse("<gray>Содержит ценнейшие реликвии и драгоценности.</gray>"),
                Component.empty(),
                MessageUtils.parse("<yellow>Нажмите <gold>ПКМ</gold>, чтобы открыть!</yellow>")
        ) : List.of(
                Component.empty(),
                MessageUtils.parse("<gray>Трофейный запечатанный ящик из потерянного каравана.</gray>"),
                MessageUtils.parse("<gray>Содержит редкие минералы и ценные ресурсы.</gray>"),
                Component.empty(),
                MessageUtils.parse("<yellow>Нажмите <gold>ПКМ</gold>, чтобы открыть!</yellow>")
        );

        ItemStack item = dev.lovelace.loveshops.utils.GuiUtils.createHead(texture, name, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(crateTypeKey, PersistentDataType.STRING, secret ? "secret" : "default");
            meta.getPersistentDataContainer().set(crateSessionKey, PersistentDataType.INTEGER, sessionId);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * Обработка открытия ящика игроком.
     */
    public boolean openCrateItem(Player player, ItemStack crate) {
        if (crate == null || !crate.hasItemMeta()) return false;
        String type = crate.getItemMeta().getPersistentDataContainer().get(crateTypeKey, PersistentDataType.STRING);
        if (type == null) return false;

        boolean isSecret = "secret".equalsIgnoreCase(type);
        List<LostCaravanLootEntry> table = isSecret ? secretLootTable : defaultLootTable;
        if (table.isEmpty()) {
            loadLootTables();
        }

        // Забираем 1 ящик
        crate.setAmount(crate.getAmount() - 1);

        // Эффекты открытия
        Location loc = player.getLocation();
        loc.getWorld().playSound(loc, Sound.BLOCK_CHEST_OPEN, 1.0f, 0.8f);
        loc.getWorld().playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.2f);
        loc.getWorld().spawnParticle(org.bukkit.Particle.FIREWORK, loc.clone().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.05);

        // Качество: 30% плохой / 50% обычный / 20% хороший (секретный чуть лучше)
        // Tiers (weights, rolls, chance scale) come from caravan.lost.quality.<default|secret>
        CrateQuality picked = CrateQuality.pick(
                CrateQuality.fromConfig(plugin.getConfig().getConfigurationSection(
                        "caravan.lost.quality." + (isSecret ? "secret" : "default")), isSecret),
                ThreadLocalRandom.current().nextDouble());
        String quality = picked.id();
        int rolls = picked.rolls();
        double chanceMul = picked.chanceMultiplier();

        List<ItemStack> rewards = new ArrayList<>();
        for (int r = 0; r < rolls && !table.isEmpty(); r++) {
            LostCaravanLootEntry entry = table.get(ThreadLocalRandom.current().nextInt(table.size()));
            double rollChance = ThreadLocalRandom.current().nextDouble(0, 100);
            if (rollChance <= entry.chance() * chanceMul) {
                int min = entry.min();
                int max = entry.max();
                if ("bad".equals(quality)) max = Math.max(min, min + (max - min) / 2);
                if ("good".equals(quality)) min = Math.max(min, (min + max) / 2);
                int count = ThreadLocalRandom.current().nextInt(min, max + 1);
                ItemStack rolled = ItemResolver.resolveItemStack(entry.itemId(), count);
                rewards.add(rolled);
            }
        }

        if (rewards.isEmpty()) {
            if ("bad".equals(quality)) {
                rewards.add(ItemResolver.resolveItemStack(isSecret ? "IRON_INGOT" : "COBBLESTONE", isSecret ? 4 : 16));
            } else if ("good".equals(quality)) {
                rewards.add(ItemResolver.resolveItemStack(isSecret ? "DIAMOND" : "GOLD_INGOT", isSecret ? 5 : 12));
            } else {
                rewards.add(ItemResolver.resolveItemStack(isSecret ? "DIAMOND" : "GOLD_INGOT", isSecret ? 3 : 8));
            }
        }

        MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");
        String qLabel = "bad".equals(quality) ? "<gray>скромный</gray>" : ("good".equals(quality) ? "<green>удачный</green>" : "<yellow>обычный</yellow>");
        MessageUtils.sendMessage(player, "<yellow>Вы открыли " + (isSecret ? "<gold>⚡ Секретный Ящик" : "📦 Ящик каравана") + "</yellow> <dark_gray>(" + qLabel + "<dark_gray>)</dark_gray>");
        MessageUtils.sendMessage(player, "<gray>Ваша награда:</gray>");
        for (ItemStack rw : rewards) {
            giveOrDropItem(player, rw);
            MessageUtils.sendMessage(player, " <dark_gray>•</dark_gray> <green>" + ItemResolver.getFriendlyRussianName(rw.getType().name()) + " x" + rw.getAmount() + "</green>");
        }
        MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");
        return true;
    }



    private void attachParticipantBossBar(Player player) {
        if (currentSession == null) return;
        if (!plugin.getConfig().getBoolean("caravan.lost.bossbar-enabled", true)) return;
        clearParticipantBossBar(player.getUniqueId());
        long openAt = currentSession.openedAt();
        long now = System.currentTimeMillis() / 1000;
        long left = Math.max(0, openAt - now);
        long total = Math.max(1L, openAt - currentSession.scheduledAt());
        float progress = Math.max(0f, Math.min(1f, (float) left / (float) total));
        BossBar bar = BossBar.bossBar(
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                        "<gold>Потерянный караван</gold> <gray>· регистрация</gray> <white>" + formatTime(left) + "</white>"),
                progress,
                BossBar.Color.YELLOW,
                BossBar.Overlay.PROGRESS
        );
        player.showBossBar(bar);
        participantBossBars.put(player.getUniqueId(), bar);
        ensureBossBarTicker();
    }

    private void ensureBossBarTicker() {
        if (bossBarTickTask != null) return;
        bossBarTickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticipantBossBars, 20L, 20L);
    }

    private void tickParticipantBossBars() {
        if (currentSession == null || !"ANNOUNCED".equalsIgnoreCase(currentSession.status())) {
            clearAllParticipantBossBars();
            return;
        }
        long openAt = currentSession.openedAt();
        long now = System.currentTimeMillis() / 1000;
        long left = Math.max(0, openAt - now);
        long total = Math.max(1L, openAt - currentSession.scheduledAt());
        float progress = Math.max(0f, Math.min(1f, (float) left / (float) total));
        var title = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<gold>Потерянный караван</gold> <gray>· до торгов</gray> <white>" + formatTime(left) + "</white>");
        for (var it = participantBossBars.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player pl = Bukkit.getPlayer(e.getKey());
            if (pl == null || !pl.isOnline()) {
                it.remove();
                continue;
            }
            BossBar bar = e.getValue();
            bar.name(title);
            bar.progress(progress);
        }
        if (left <= 0) {
            clearAllParticipantBossBars();
        }
    }

    private static String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    private void clearParticipantBossBar(UUID uuid) {
        BossBar bar = participantBossBars.remove(uuid);
        if (bar == null) return;
        Player pl = Bukkit.getPlayer(uuid);
        if (pl != null) pl.hideBossBar(bar);
    }

    private void clearAllParticipantBossBars() {
        for (UUID uuid : new java.util.ArrayList<>(participantBossBars.keySet())) {
            clearParticipantBossBar(uuid);
        }
        if (bossBarTickTask != null) {
            bossBarTickTask.cancel();
            bossBarTickTask = null;
        }
    }

    public synchronized boolean forceStart(String mode, boolean force) {
        if (isEventActive() && !force) {
            return false;
        }
        if (isEventActive()) {
            closeSession();
        }
        announceSession(System.currentTimeMillis() / 1000);
        if (currentSession != null) {
            pendingForceMode = mode == null ? "AUTO" : mode.trim().toUpperCase();
        }
        return true;
    }

    /**
     * Admin shortcut: ends the timer of the current phase right now. Registration closes and the trading
     * opens, a running lot round is settled, the settling phase closes the session.
     *
     * @return the phase that was skipped ("REGISTRATION", "LOT", "SETTLING") or {@code null} when no event runs
     */
    public synchronized @Nullable String skipTimer() {
        if (currentSession == null) return null;
        String status = currentSession.status();
        if ("ANNOUNCED".equalsIgnoreCase(status)) {
            openSession();
            return "REGISTRATION";
        }
        if ("OPEN".equalsIgnoreCase(status)) {
            if ("AUCTION".equalsIgnoreCase(currentSession.mode()) && currentAuctionLotIndex >= 0) {
                endCurrentLotRound();
                return "LOT";
            }
            return "INSTANT";
        }
        if ("SETTLING".equalsIgnoreCase(status)) {
            closeSession();
            return "SETTLING";
        }
        return null;
    }

    public void handleNpcClick(Player player) {
        if (currentSession == null) {
            MessageUtils.sendMessage(player, "<yellow>Потерянный караван ещё не прибыл.</yellow>");
            return;
        }

        if ("ANNOUNCED".equalsIgnoreCase(currentSession.status())) {
            plugin.getNpcDialogueManager().sayLostCaravanGreeting(player);
            new LostCaravanEntryGui(plugin, player, this).open();
            return;
        }

        if ("OPEN".equalsIgnoreCase(currentSession.status())) {
            if (!isParticipant(currentSession.id(), player.getUniqueId())) {
                MessageUtils.sendMessage(player, "<gray>Вы не вносили залог. Меню лотов доступно только участникам.</gray>");
                return;
            }
            plugin.getNpcDialogueManager().sayLostCaravanGreeting(player);
            if ("AUCTION".equalsIgnoreCase(currentSession.mode())) {
                new LostCaravanAuctionGui(plugin, player, this).open();
            } else {
                new LostCaravanInstantGui(plugin, player, this).open();
            }
            return;
        }

        if ("SETTLING".equalsIgnoreCase(currentSession.status())) {
            MessageUtils.sendMessage(player, "<gray>Торги завершены, караван сворачивает лагерь.</gray>");
        }
    }

    private void giveOrDropItem(Player player, ItemStack item) {
        var leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (ItemStack drop : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            MessageUtils.sendMessage(player, "<yellow>Ваш инвентарь был полон, часть предметов выпала на землю!</yellow>");
        }
    }

    private void spawnCaravanNpc() {
        for (NpcData npc : plugin.getNpcManager().getAllNpcs()) {
            if ("lostcaravan".equalsIgnoreCase(npc.type())) {
                plugin.getNpcManager().spawnNpcEntity(npc);
            }
        }
    }

    private void despawnCaravanNpc() {
        for (NpcData npc : plugin.getNpcManager().getAllNpcs()) {
            if ("lostcaravan".equalsIgnoreCase(npc.type())) {
                plugin.getNpcManager().despawnNpcEntity(npc.uuid());
            }
        }
    }
}
