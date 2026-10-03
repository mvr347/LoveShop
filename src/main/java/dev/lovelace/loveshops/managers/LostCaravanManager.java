package dev.lovelace.loveshops.managers;

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
        COOLDOWN,
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

    private final LoveShops plugin;
    private final NamespacedKey crateTypeKey;
    private final NamespacedKey crateSessionKey;

    private LostCaravanSession currentSession;
    private final List<LostCaravanLot> activeLots = new ArrayList<>();
    private final List<LostCaravanLootEntry> defaultLootTable = new ArrayList<>();
    private final List<LostCaravanLootEntry> secretLootTable = new ArrayList<>();

    // Состояние текущего лота на аукционе
    private int currentAuctionLotIndex = -1;
    private long currentLotEndTimestamp = 0;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask schedulerTask;
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

    public long getCurrentLotTimeRemainingSeconds() {
        return Math.max(0, currentLotEndTimestamp - (System.currentTimeMillis() / 1000));
    }

    public void start() {
        if (!isEnabled()) return;
        // Проверка расписания каждые 30 секунд
        schedulerTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            checkSchedule();
        }, 15, 30, TimeUnit.SECONDS);
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
        stopAuctionTicker();
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
        int minForAuction = plugin.getConfig().getInt("caravan.lost.min-players-for-auction", 6);
        boolean secretCrate = participantCount >= plugin.getConfig().getInt("caravan.lost.secret-crate-chance-players", 12);
        String mode = participantCount <= minForAuction ? "INSTANT" : "AUCTION";

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
    }

    private void createLots(int sessionId, boolean secretCrate) {
        activeLots.clear();
        int baseCratesCount = plugin.getConfig().getInt("caravan.lost.crates-count", 5);
        int totalCrates = secretCrate ? baseCratesCount + 1 : baseCratesCount;
        int startingPrice = plugin.getConfig().getInt("caravan.lost.starting-price", 10);

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

        String crateName = lot.secret() ? "<red><bold>⚡ СЕКРЕТНЫЙ ЯЩИК</bold></red>" : "<gold>Ящик #" + (lotIndex + 1) + "</gold>";
        CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <yellow>Открыты торги за "
                + crateName + "! Начальная ставка: " + lot.startingPrice() + " монет. Время: " + durationSec + " сек.</yellow>");
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
                MessageUtils.sendMessage(winner, "<green><bold>Поздравляем!</bold> Вы выиграли "
                        + (lot.secret() ? "Секретный Ящик" : "Ящик каравана") + " со ставкой " + lot.currentBid() + " монет!</green>");
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

        CaravanEffects.broadcast("<gold><bold>⚔ [Потерянный Караван]</bold></gold> <yellow>Все торги завершены! Караван простоит ещё "
                + settleMinutes + " мин. для расчётов.</yellow>");
    }

    public synchronized void closeSession() {
        stopAuctionTicker();
        if (currentSession == null) return;
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

        int minBid = lot.currentBid() > 0 ? (lot.currentBid() + Math.max(1, (int) Math.round(lot.currentBid() * 0.05))) : lot.startingPrice();
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

        // 2. Возврат ставки предыдущему лидеру
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

        // 3. Обновление БД
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
            return BidResult.DB_ERROR;
        }

        // 4. Обновление в памяти
        activeLots.set(currentAuctionLotIndex, new LostCaravanLot(
                lot.id(), lot.sessionId(), lot.lotIndex(), lot.secret(), lot.crateItem(),
                lot.startingPrice(), amount, player.getUniqueId(), "ACTIVE", lot.startedAt(), lot.endedAt()
        ));

        // Анти-снайп: если до конца осталось < 10 сек, продлеваем на 15 сек
        long remaining = currentLotEndTimestamp - (System.currentTimeMillis() / 1000);
        if (remaining < 10) {
            currentLotEndTimestamp += 15;
            CaravanEffects.broadcast("<yellow>[Анти-снайп] Торги за ящик продлены на 15 секунд!</yellow>");
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
            } catch (SQLException ignored) {}
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

        // Проверка кулдауна (7 дней)
        long cooldownSec = 7L * 86400L;
        long last = getPlayerLastParticipated(uuid);
        long now = System.currentTimeMillis() / 1000;
        if (last > 0 && (now - last) < cooldownSec) {
            return RegisterResult.COOLDOWN;
        }

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        int entryFee = plugin.getConfig().getInt("caravan.lost.entry-fee.amount", 1);
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

            PreparedStatement psCd = conn.prepareStatement("""
                INSERT INTO caravan_cooldowns (player_uuid, last_participated) VALUES (?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET last_participated = excluded.last_participated
            """);
            psCd.setString(1, uuid.toString());
            psCd.setLong(2, now);
            psCd.executeUpdate();

            PreparedStatement psCount = conn.prepareStatement("""
                UPDATE lost_caravan_sessions SET participant_count = participant_count + 1 WHERE id = ?
            """);
            psCount.setInt(1, currentSession.id());
            psCount.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка регистрации участника Lost Caravan: " + e.getMessage());
            return RegisterResult.DB_ERROR;
        }

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        MessageUtils.sendMessage(player, "<green><bold>Вы успешно зарегистрировались в Потерянном Караване!</bold></green>");
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
                        MessageUtils.sendMessage(p, "<yellow>[Потерянный Караван] Вам возвращено 50% залога: "
                                + CoinFormat.formatGlyphs(eco, refundAmount) + ".</yellow>");
                    } else {
                        addPendingRefund(uuid, refundAmount);
                        MessageUtils.sendMessage(p, "<yellow>[Потерянный Караван] Вам возвращено 50% залога ("
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

    public long getPlayerLastParticipated(UUID uuid) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT last_participated FROM caravan_cooldowns WHERE player_uuid = ?");
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("last_participated");
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения кулдауна: " + e.getMessage());
        }
        return 0;
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
        ItemStack item = new ItemStack(Material.CHEST);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (secret) {
                meta.displayName(MessageUtils.parse("<gradient:#FF55FF:#FFAA00><bold>⚡ Секретный Ящик Каравана</bold></gradient>"));
                meta.lore(List.of(
                        Component.empty(),
                        MessageUtils.parse("<gray>Редчайший запечатанный ящик из глубин каравана.</gray>"),
                        MessageUtils.parse("<gray>Содержит ценнейшие реликвии и драгоценности.</gray>"),
                        Component.empty(),
                        MessageUtils.parse("<yellow>Нажмите <gold>ПКМ</gold>, чтобы открыть!</yellow>")
                ));
            } else {
                meta.displayName(MessageUtils.parse("<gradient:#FFAA00:#FF5555><bold>📦 Ящик Потерянного Каравана</bold></gradient>"));
                meta.lore(List.of(
                        Component.empty(),
                        MessageUtils.parse("<gray>Трофейный запечатанный ящик из потерянного каравана.</gray>"),
                        MessageUtils.parse("<gray>Содержит редкие минералы и ценные ресурсы.</gray>"),
                        Component.empty(),
                        MessageUtils.parse("<yellow>Нажмите <gold>ПКМ</gold>, чтобы открыть!</yellow>")
                ));
            }
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

        // Роллим от 2 до 4 предметов из лут-таблицы
        List<ItemStack> rewards = new ArrayList<>();
        int rolls = ThreadLocalRandom.current().nextInt(2, 5);

        for (int r = 0; r < rolls && !table.isEmpty(); r++) {
            LostCaravanLootEntry entry = table.get(ThreadLocalRandom.current().nextInt(table.size()));
            double rollChance = ThreadLocalRandom.current().nextDouble(0, 100);
            if (rollChance <= entry.chance()) {
                int count = ThreadLocalRandom.current().nextInt(entry.min(), entry.max() + 1);
                ItemStack rolled = ItemResolver.resolveItemStack(entry.itemId(), count);
                rewards.add(rolled);
            }
        }

        // Если ничего не выпало, даём гарантированно хотя бы золото/железо
        if (rewards.isEmpty()) {
            rewards.add(ItemResolver.resolveItemStack(isSecret ? "DIAMOND" : "GOLD_INGOT", isSecret ? 3 : 8));
        }

        MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");
        MessageUtils.sendMessage(player, "<yellow>Вы открыли " + (isSecret ? "<gold>⚡ Секретный Ящик" : "📦 Ящик каравана") + "!</yellow>");
        MessageUtils.sendMessage(player, "<gray>Ваша награда:</gray>");
        for (ItemStack rw : rewards) {
            giveOrDropItem(player, rw);
            MessageUtils.sendMessage(player, " <dark_gray>•</dark_gray> <green>" + rw.getType().name() + " x" + rw.getAmount() + "</green>");
        }
        MessageUtils.sendMessage(player, "<gold>══════════════════════════════════</gold>");
        return true;
    }

    public void handleNpcClick(Player player) {
        if (currentSession == null) {
            MessageUtils.sendMessage(player, "<yellow>Потерянный караван ещё не прибыл.</yellow>");
            return;
        }

        if ("ANNOUNCED".equalsIgnoreCase(currentSession.status())) {
            new LostCaravanEntryGui(plugin, player, this).open();
        } else if ("OPEN".equalsIgnoreCase(currentSession.status())) {
            if ("AUCTION".equalsIgnoreCase(currentSession.mode())) {
                new LostCaravanAuctionGui(plugin, player, this).open();
            } else {
                new LostCaravanInstantGui(plugin, player, this).open();
            }
        } else if ("SETTLING".equalsIgnoreCase(currentSession.status())) {
            MessageUtils.sendMessage(player, "<gray>[Потерянный Караван] Торги уже завершены, караван сворачивает лагерь.</gray>");
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
