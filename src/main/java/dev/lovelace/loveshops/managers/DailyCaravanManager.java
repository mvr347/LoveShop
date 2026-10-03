package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.gui.DailyCaravanGui;
import dev.lovelace.loveshops.models.NpcData;
import dev.lovelace.loveshops.models.caravan.DailyCrateAcceptedItem;
import dev.lovelace.loveshops.models.caravan.DailyCrateConfig;
import dev.lovelace.loveshops.models.caravan.DailyCrateState;
import dev.lovelace.loveshops.utils.CaravanEffects;
import dev.lovelace.loveshops.utils.CoinFormat;
import dev.lovelace.loveshops.utils.ItemResolver;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Менеджер Ежедневного Караванщика (Daily Caravaner):
 * - Расписание визитов и генерация случайного набора ящиков из пула
 * - Срочные заказы (Urgent Orders)
 * - Приём товаров от игроков с выплатой физических монет LoveEconomy
 * - Бонус за сдачу целыми стаками (+8-10%)
 * - Ограничение на количество сданных стаков за визит (защита от абуза)
 * - Оповещение «скоро уезжает» при 1 оставшемся ящике или за 15 минут до конца
 */
public class DailyCaravanManager {

    public enum SubmitResult {
        SUCCESS,
        NO_ITEMS,
        CRATE_CLOSED,
        LIMIT_REACHED,
        NO_SPACE,
        ERROR
    }

    private final LoveShops plugin;
    private final Random random = new Random();

    private final Map<String, DailyCrateConfig> cratePool = new HashMap<>();
    private final List<DailyCrateState> activeCrates = new CopyOnWriteArrayList<>();

    private int currentVisitId = -1;
    private long visitSpawnedAt = 0;
    private long visitDespawnAt = 0;
    private boolean active = false;
    private boolean almostLeavingAnnounced = false;
    private LocalDate lastVisitDate = null;

    private BukkitTask scheduleTask;

    public DailyCaravanManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadConfig();
        loadActiveVisitFromDb();

        // Проверка расписания каждые 30 секунд (600 тиков)
        scheduleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSchedule, 100L, 600L);
    }

    public void stop() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
            scheduleTask = null;
        }
    }

    public boolean isCaravanerActive() {
        return active;
    }

    public List<DailyCrateState> getActiveCrates() {
        return Collections.unmodifiableList(activeCrates);
    }

    public long getVisitDespawnAt() {
        return visitDespawnAt;
    }

    public int getCurrentVisitId() {
        return currentVisitId;
    }

    public DailyCrateConfig getCrateConfig(String key) {
        return cratePool.get(key);
    }

    public void loadConfig() {
        cratePool.clear();
        ConfigurationSection poolSec = plugin.getConfig().getConfigurationSection("caravan.daily.crate-pool");
        if (poolSec == null) {
            plugin.getLogger().warning("Секция caravan.daily.crate-pool не найдена в config.yml");
            return;
        }

        for (String key : poolSec.getKeys(false)) {
            ConfigurationSection sec = poolSec.getConfigurationSection(key);
            if (sec == null) continue;

            String displayName = sec.getString("display-name", "<gold>Ящик</gold>");
            String icon = sec.getString("icon", "CHEST");
            int maxStacks = Math.max(1, sec.getInt("max-stacks", 30));
            int stackBonus = Math.max(0, sec.getInt("stack-bonus-percent", 8));

            List<DailyCrateAcceptedItem> accepted = new ArrayList<>();
            List<Map<?, ?>> acceptedList = sec.getMapList("accepted");
            for (Map<?, ?> itemMap : acceptedList) {
                String mat = itemMap.get("material") != null ? String.valueOf(itemMap.get("material")) : null;
                if (mat == null && itemMap.get("itemsadder") != null) {
                    mat = String.valueOf(itemMap.get("itemsadder"));
                }
                int price = itemMap.get("price-per-unit") != null
                        ? Integer.parseInt(String.valueOf(itemMap.get("price-per-unit"))) : 1;
                if (mat != null) {
                    accepted.add(new DailyCrateAcceptedItem(mat, price));
                }
            }

            cratePool.put(key, new DailyCrateConfig(key, displayName, icon, accepted, maxStacks, stackBonus));
        }
    }

    private void tickSchedule() {
        if (!plugin.getConfig().getBoolean("caravan.daily.enabled", true)) {
            if (active) endVisit("DISABLED");
            return;
        }

        long nowSec = System.currentTimeMillis() / 1000;

        if (active) {
            // 1. Проверка истечения времени визита
            if (nowSec >= visitDespawnAt) {
                endVisit("TIME_UP");
                return;
            }

            // 2. Проверка срочных заказов
            for (DailyCrateState crate : activeCrates) {
                if (crate.isUrgent() && !crate.isClosed() && crate.urgentExpiresAt() > 0 && nowSec >= crate.urgentExpiresAt()) {
                    crate.setClosed(true);
                    updateCrateInDb(crate);
                    CaravanEffects.broadcast("<gold>[Караванщик]</gold> <red>Время срочного заказа на «"
                            + crate.displayName() + "» истекло!</red>");
                }
            }

            // 3. Проверка предупреждения «скоро уезжает»
            int openCount = 0;
            for (DailyCrateState crate : activeCrates) {
                if (!crate.isClosed()) openCount++;
            }

            if (openCount == 0) {
                endVisit("FULL");
                return;
            }

            int thresholdMin = Math.max(1, plugin.getConfig().getInt("caravan.daily.almost-leaving-threshold-minutes", 15));
            boolean almostLeaving = openCount == 1 || (visitDespawnAt - nowSec) <= (thresholdMin * 60L);
            if (almostLeaving && !almostLeavingAnnounced) {
                almostLeavingAnnounced = true;
                Component msg = plugin.getLangManager().getMessage("caravan.daily.almost-leaving",
                        "<gold><bold>⚡ Караванщик скоро уезжает!</bold></gold> <yellow>Осталось мало времени или почти все ящики заполнены.</yellow>");
                CaravanEffects.broadcast(msg);
            }
        } else {
            // Проверка наступления времени визита
            String spawnTimeStr = plugin.getConfig().getString("caravan.daily.spawn-time", "12:00");
            try {
                LocalTime spawnTime = LocalTime.parse(spawnTimeStr, DateTimeFormatter.ofPattern("HH:mm"));
                LocalTime nowTime = LocalTime.now();
                LocalDate today = LocalDate.now();

                if (!today.equals(lastVisitDate) && nowTime.isAfter(spawnTime)
                        && nowTime.isBefore(spawnTime.plusHours(plugin.getConfig().getInt("caravan.daily.duration-hours", 4)))) {
                    startVisit(false);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка парсинга времени caravan.daily.spawn-time: " + e.getMessage());
            }
        }
    }

    public synchronized boolean startVisit(boolean manual) {
        if (active) return false;
        if (cratePool.isEmpty()) loadConfig();
        if (cratePool.isEmpty()) {
            plugin.getLogger().warning("Невозможно запустить Караванщика: пул ящиков пуст!");
            return false;
        }

        long nowSec = System.currentTimeMillis() / 1000;
        int durationHours = Math.max(1, plugin.getConfig().getInt("caravan.daily.duration-hours", 4));
        long despawnSec = nowSec + (durationHours * 3600L);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO daily_caravan_visits (spawned_at, despawn_at, status) VALUES (?, ?, 'ACTIVE')",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, nowSec);
            ps.setLong(2, despawnSec);
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            if (!rs.next()) return false;
            currentVisitId = rs.getInt(1);
            visitSpawnedAt = nowSec;
            visitDespawnAt = despawnSec;
            active = true;
            almostLeavingAnnounced = false;
            lastVisitDate = LocalDate.now();

            // Генерация ящиков
            activeCrates.clear();
            generateCratesForVisit(conn, currentVisitId, nowSec);

        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка создания визита Караванщика в БД: " + e.getMessage());
            return false;
        }

        // Обновление состояния NPC
        respawnCaravanerNpc();

        // Оповещение и эффекты
        Location npcLoc = getCaravanerNpcLocation();
        CaravanEffects.playArrivalEffects(npcLoc);

        Component broadcastMsg = plugin.getLangManager().getMessage("caravan.daily.arrival",
                "<gold><bold>📦 Караванщик прибыл в город!</bold></gold> <yellow>Приём товаров открыт на {hours} ч. Успейте сдать ресурсы!</yellow>",
                Map.of("hours", String.valueOf(durationHours)));
        CaravanEffects.broadcast(broadcastMsg);

        return true;
    }

    private void generateCratesForVisit(Connection conn, int visitId, long nowSec) throws SQLException {
        int minCrates = Math.max(1, plugin.getConfig().getInt("caravan.daily.crates-per-visit.min", 3));
        int maxCrates = Math.max(minCrates, plugin.getConfig().getInt("caravan.daily.crates-per-visit.max", 5));
        int count = minCrates + (maxCrates > minCrates ? random.nextInt(maxCrates - minCrates + 1) : 0);

        List<DailyCrateConfig> poolList = new ArrayList<>(cratePool.values());
        Collections.shuffle(poolList, random);

        int variancePercent = Math.max(0, plugin.getConfig().getInt("caravan.daily.price-variance-percent", 8));

        int selectedCount = Math.min(count, poolList.size());
        for (int i = 0; i < selectedCount; i++) {
            DailyCrateConfig cfg = poolList.get(i);
            int basePrice = cfg.acceptedItems().isEmpty() ? 1 : cfg.acceptedItems().get(0).pricePerUnit();
            int variance = variancePercent > 0 ? (random.nextInt(variancePercent * 2 + 1) - variancePercent) : 0;
            int finalPrice = Math.max(1, Math.round(basePrice * (1.0f + (variance / 100.0f))));
            int maxAmount = cfg.maxStacks() * 64;

            insertCrate(conn, visitId, cfg.key(), cfg.displayName(), maxAmount, finalPrice, false, 0);
        }

        // Ролл срочного заказа
        boolean urgentEnabled = plugin.getConfig().getBoolean("caravan.daily.urgent-order.enabled", true);
        double urgentChance = plugin.getConfig().getDouble("caravan.daily.urgent-order.chance-per-visit", 0.25);
        if (urgentEnabled && random.nextDouble() < urgentChance && !poolList.isEmpty()) {
            DailyCrateConfig urgentCfg = poolList.get(random.nextInt(poolList.size()));
            int urgentDurationMin = Math.max(5, plugin.getConfig().getInt("caravan.daily.urgent-order.duration-minutes", 60));
            double multiplier = Math.max(1.0, plugin.getConfig().getDouble("caravan.daily.urgent-order.price-multiplier", 1.8));
            int basePrice = urgentCfg.acceptedItems().isEmpty() ? 1 : urgentCfg.acceptedItems().get(0).pricePerUnit();
            int urgentPrice = Math.max(1, (int) Math.round(basePrice * multiplier));
            int maxAmount = Math.max(10, (urgentCfg.maxStacks() / 2)) * 64;
            String urgentName = plugin.getConfig().getString("caravan.daily.urgent-order.display-name",
                    "<red><bold>⚡ СРОЧНЫЙ ЗАКАЗ: </bold></red>") + " " + urgentCfg.displayName();
            long urgentExpires = nowSec + (urgentDurationMin * 60L);

            insertCrate(conn, visitId, urgentCfg.key(), urgentName, maxAmount, urgentPrice, true, urgentExpires);
        }
    }

    private void insertCrate(Connection conn, int visitId, String key, String name,
                             int maxAmount, int unitPrice, boolean isUrgent, long urgentExpires) throws SQLException {
        PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO daily_caravan_crates (visit_id, crate_key, display_name, current_amount, max_amount, price_per_unit, is_urgent, urgent_expires_at, closed) " +
                        "VALUES (?, ?, ?, 0, ?, ?, ?, ?, 0)",
                Statement.RETURN_GENERATED_KEYS
        );
        ps.setInt(1, visitId);
        ps.setString(2, key);
        ps.setString(3, name);
        ps.setInt(4, maxAmount);
        ps.setInt(5, unitPrice);
        ps.setInt(6, isUrgent ? 1 : 0);
        ps.setLong(7, urgentExpires);
        ps.executeUpdate();

        ResultSet rs = ps.getGeneratedKeys();
        if (rs.next()) {
            int crateId = rs.getInt(1);
            activeCrates.add(new DailyCrateState(crateId, visitId, key, name, 0, maxAmount, unitPrice, isUrgent, urgentExpires, false));
        }
    }

    public synchronized void endVisit(String reason) {
        if (!active) return;
        active = false;

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("UPDATE daily_caravan_visits SET status = ? WHERE id = ?");
            ps.setString(1, reason);
            ps.setInt(2, currentVisitId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка закрытия визита Караванщика: " + e.getMessage());
        }

        Location npcLoc = getCaravanerNpcLocation();
        CaravanEffects.playDepartureEffects(npcLoc);

        respawnCaravanerNpc();

        Component msg;
        if ("FULL".equalsIgnoreCase(reason)) {
            msg = plugin.getLangManager().getMessage("caravan.daily.departure-full",
                    "<gold><bold>📦 Все ящики Караванщика заполнены!</bold></gold> <yellow>Караван покидает город до следующего визита.</yellow>");
        } else {
            msg = plugin.getLangManager().getMessage("caravan.daily.departure-time",
                    "<gold><bold>📦 Время визита Караванщика истекло.</bold></gold> <gray>Караван отправился в путь.</gray>");
        }
        CaravanEffects.broadcast(msg);

        activeCrates.clear();
        currentVisitId = -1;
    }

    private void loadActiveVisitFromDb() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, spawned_at, despawn_at FROM daily_caravan_visits WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1"
            );
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                currentVisitId = rs.getInt("id");
                visitSpawnedAt = rs.getLong("spawned_at");
                visitDespawnAt = rs.getLong("despawn_at");
                long now = System.currentTimeMillis() / 1000;

                if (now >= visitDespawnAt) {
                    endVisit("TIME_UP");
                    return;
                }

                active = true;
                activeCrates.clear();

                PreparedStatement psCrates = conn.prepareStatement(
                        "SELECT id, visit_id, crate_key, display_name, current_amount, max_amount, price_per_unit, is_urgent, urgent_expires_at, closed " +
                                "FROM daily_caravan_crates WHERE visit_id = ?"
                );
                psCrates.setInt(1, currentVisitId);
                ResultSet rsCrates = psCrates.executeQuery();
                while (rsCrates.next()) {
                    activeCrates.add(new DailyCrateState(
                            rsCrates.getInt("id"),
                            rsCrates.getInt("visit_id"),
                            rsCrates.getString("crate_key"),
                            rsCrates.getString("display_name"),
                            rsCrates.getInt("current_amount"),
                            rsCrates.getInt("max_amount"),
                            rsCrates.getInt("price_per_unit"),
                            rsCrates.getInt("is_urgent") == 1,
                            rsCrates.getLong("urgent_expires_at"),
                            rsCrates.getInt("closed") == 1
                    ));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка загрузки активного визита Караванщика: " + e.getMessage());
        }
    }

    /**
     * Сдача предметов в ящик Караванщика.
     */
    public synchronized SubmitResult submitItems(Player player, int crateId, boolean allMatching) {
        if (!active || player == null || !player.isOnline()) return SubmitResult.CRATE_CLOSED;

        DailyCrateState targetCrate = null;
        for (DailyCrateState c : activeCrates) {
            if (c.id() == crateId) {
                targetCrate = c;
                break;
            }
        }

        if (targetCrate == null || targetCrate.isClosed()) {
            return SubmitResult.CRATE_CLOSED;
        }

        DailyCrateConfig cfg = cratePool.get(targetCrate.crateKey());
        if (cfg == null) return SubmitResult.ERROR;

        int remainingCap = targetCrate.remainingAmount();
        if (remainingCap <= 0) {
            targetCrate.setClosed(true);
            updateCrateInDb(targetCrate);
            return SubmitResult.CRATE_CLOSED;
        }

        // Проверка лимита сдачи игрока за визит (анти-абуз)
        int maxStacksPerVisit = Math.max(1, plugin.getConfig().getInt("caravan.daily.max-stacks-per-player-visit", 15));
        int alreadySubmitted = getPlayerSubmittedUnits(player.getUniqueId(), currentVisitId);
        int remainingPlayerAllowance = (maxStacksPerVisit * 64) - alreadySubmitted;
        if (remainingPlayerAllowance <= 0) {
            return SubmitResult.LIMIT_REACHED;
        }

        int maxToAccept = Math.min(remainingCap, remainingPlayerAllowance);

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null) {
            plugin.getLogger().warning("LoveEconomy недоступен для выплаты каравана!");
            return SubmitResult.ERROR;
        }

        // Поиск подходящих предметов в инвентаре игрока
        ItemStack held = player.getInventory().getItemInMainHand();
        List<ItemStack> candidates = new ArrayList<>();

        if (!allMatching) {
            // Только предмет в руке
            if (matchesAny(held, cfg.acceptedItems())) {
                candidates.add(held);
            }
        } else {
            // Все подходящие предметы из основного хранилища инвентаря (без брони)
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (item != null && !item.getType().isAir() && matchesAny(item, cfg.acceptedItems())) {
                    candidates.add(item);
                }
            }
        }

        if (candidates.isEmpty()) {
            return SubmitResult.NO_ITEMS;
        }

        // ПАСС 1: Расчёт количества и выплаты БЕЗ изменения предметов
        int totalUnitsCollected = 0;
        long totalCoinsEarned = 0;
        record Deduction(ItemStack item, int take) {}
        List<Deduction> deductions = new ArrayList<>();

        for (ItemStack is : candidates) {
            if (totalUnitsCollected >= maxToAccept) break;
            int take = Math.min(is.getAmount(), maxToAccept - totalUnitsCollected);
            if (take <= 0) continue;

            DailyCrateAcceptedItem acceptedItem = getMatchedAcceptedItem(is, cfg.acceptedItems());
            int unitPrice = targetCrate.pricePerUnit();

            // Расчёт бонуса за полные стаки
            long itemCoins;
            if (take == is.getMaxStackSize() && cfg.stackBonusPercent() > 0) {
                double bonus = 1.0 + (cfg.stackBonusPercent() / 100.0);
                itemCoins = Math.round(take * unitPrice * bonus);
            } else {
                itemCoins = (long) take * unitPrice;
            }

            deductions.add(new Deduction(is, take));
            totalUnitsCollected += take;
            totalCoinsEarned += itemCoins;
        }

        if (totalUnitsCollected <= 0 || deductions.isEmpty()) {
            return SubmitResult.NO_ITEMS;
        }

        // Проверка свободного места для монет ДО изменения инвентаря
        if (!eco.canFit(player, totalCoinsEarned)) {
            return SubmitResult.NO_SPACE;
        }

        // ПАСС 2: Списание предметов только после успешной проверки
        for (Deduction d : deductions) {
            d.item().setAmount(d.item().getAmount() - d.take());
        }

        // Выплата монет LoveEconomy
        eco.give(player, totalCoinsEarned);

        // Обновление состояния в БД
        int newCurrentAmount = targetCrate.currentAmount() + totalUnitsCollected;
        targetCrate.setCurrentAmount(newCurrentAmount);
        if (newCurrentAmount >= targetCrate.maxAmount()) {
            targetCrate.setClosed(true);
        }
        updateCrateInDb(targetCrate);
        recordSubmission(targetCrate.id(), player.getUniqueId(), totalUnitsCollected, totalCoinsEarned);

        // Обновление открытых GUI у всех зрителей
        DailyCaravanGui.refreshAll(plugin);

        // Эффекты
        CaravanEffects.playSubmitEffects(player);

        Component successMsg = plugin.getLangManager().getMessage("caravan.daily.submit-success",
                "<green>Вы сдали <gold>{count} шт.</gold> в «{name}» и получили {money}!</green>",
                Map.of("count", String.valueOf(totalUnitsCollected),
                        "name", targetCrate.displayName(),
                        "money", CoinFormat.formatGlyphs(eco, totalCoinsEarned)));
        MessageUtils.sendMessage(player, successMsg);

        // Проверка завершения визита
        boolean allClosed = true;
        for (DailyCrateState c : activeCrates) {
            if (!c.isClosed()) {
                allClosed = false;
                break;
            }
        }
        if (allClosed) {
            endVisit("FULL");
        }

        return SubmitResult.SUCCESS;
    }

    public synchronized SubmitResult submitCursorItem(Player player, int crateId, ItemStack cursor) {
        if (!active || cursor == null || cursor.getType().isAir()) return SubmitResult.NO_ITEMS;
        DailyCrateState targetCrate = null;
        for (DailyCrateState c : activeCrates) {
            if (c.id() == crateId) {
                targetCrate = c;
                break;
            }
        }
        if (targetCrate == null || targetCrate.isClosed()) return SubmitResult.CRATE_CLOSED;

        DailyCrateConfig cfg = cratePool.get(targetCrate.crateKey());
        if (cfg == null) return SubmitResult.ERROR;

        if (!matchesAny(cursor, cfg.acceptedItems())) {
            return SubmitResult.NO_ITEMS;
        }

        int remainingCap = targetCrate.remainingAmount();
        if (remainingCap <= 0) {
            targetCrate.setClosed(true);
            updateCrateInDb(targetCrate);
            return SubmitResult.CRATE_CLOSED;
        }

        int maxStacksPerVisit = Math.max(1, plugin.getConfig().getInt("caravan.daily.max-stacks-per-player-visit", 15));
        int alreadySubmitted = getPlayerSubmittedUnits(player.getUniqueId(), currentVisitId);
        int remainingPlayerAllowance = (maxStacksPerVisit * 64) - alreadySubmitted;
        if (remainingPlayerAllowance <= 0) {
            return SubmitResult.LIMIT_REACHED;
        }

        int maxToAccept = Math.min(remainingCap, remainingPlayerAllowance);
        int take = Math.min(cursor.getAmount(), maxToAccept);
        if (take <= 0) return SubmitResult.NO_ITEMS;

        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null) return SubmitResult.ERROR;

        int unitPrice = targetCrate.pricePerUnit();
        long itemCoins;
        if (take == cursor.getMaxStackSize() && cfg.stackBonusPercent() > 0) {
            double bonus = 1.0 + (cfg.stackBonusPercent() / 100.0);
            itemCoins = Math.round(take * unitPrice * bonus);
        } else {
            itemCoins = (long) take * unitPrice;
        }

        if (!eco.canFit(player, itemCoins)) {
            return SubmitResult.NO_SPACE;
        }

        // Снимаем с курсора
        int left = cursor.getAmount() - take;
        if (left > 0) {
            cursor.setAmount(left);
        } else {
            player.setItemOnCursor(null);
        }

        eco.give(player, itemCoins);

        int newCurrentAmount = targetCrate.currentAmount() + take;
        targetCrate.setCurrentAmount(newCurrentAmount);
        if (newCurrentAmount >= targetCrate.maxAmount()) {
            targetCrate.setClosed(true);
        }
        updateCrateInDb(targetCrate);
        recordSubmission(targetCrate.id(), player.getUniqueId(), take, itemCoins);

        DailyCaravanGui.refreshAll(plugin);
        CaravanEffects.playSubmitEffects(player);

        Component successMsg = plugin.getLangManager().getMessage("caravan.daily.submit-success",
                "<green>Вы сдали <gold>{count} шт.</gold> в «{name}» и получили {money}!</green>",
                Map.of("count", String.valueOf(take),
                        "name", targetCrate.displayName(),
                        "money", CoinFormat.formatGlyphs(eco, itemCoins)));
        MessageUtils.sendMessage(player, successMsg);

        boolean allClosed = true;
        for (DailyCrateState c : activeCrates) {
            if (!c.isClosed()) {
                allClosed = false;
                break;
            }
        }
        if (allClosed) {
            endVisit("FULL");
        }

        return SubmitResult.SUCCESS;
    }

    private boolean matchesAny(ItemStack is, List<DailyCrateAcceptedItem> accepted) {
        if (is == null || is.getType().isAir()) return false;
        for (DailyCrateAcceptedItem acc : accepted) {
            if (ItemResolver.matches(is, acc.itemId())) return true;
        }
        return false;
    }

    private DailyCrateAcceptedItem getMatchedAcceptedItem(ItemStack is, List<DailyCrateAcceptedItem> accepted) {
        for (DailyCrateAcceptedItem acc : accepted) {
            if (ItemResolver.matches(is, acc.itemId())) return acc;
        }
        return null;
    }

    private int getPlayerSubmittedUnits(UUID playerUuid, int visitId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement("""
                SELECT COALESCE(SUM(s.amount), 0) AS total_submitted
                FROM daily_caravan_submissions s
                JOIN daily_caravan_crates c ON s.crate_id = c.id
                WHERE c.visit_id = ? AND s.player_uuid = ?
            """);
            ps.setInt(1, visitId);
            ps.setString(2, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("total_submitted");
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка получения сданных стаков игрока: " + e.getMessage());
        }
        return 0;
    }

    private void updateCrateInDb(DailyCrateState crate) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE daily_caravan_crates SET current_amount = ?, closed = ? WHERE id = ?"
            );
            ps.setInt(1, crate.currentAmount());
            ps.setInt(2, crate.isClosed() ? 1 : 0);
            ps.setInt(3, crate.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка обновления ящика в БД: " + e.getMessage());
        }
    }

    private void recordSubmission(int crateId, UUID playerUuid, int amount, long paid) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO daily_caravan_submissions (crate_id, player_uuid, amount, paid) VALUES (?, ?, ?, ?)"
            );
            ps.setInt(1, crateId);
            ps.setString(2, playerUuid.toString());
            ps.setInt(3, amount);
            ps.setLong(4, paid);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка записи сдачи в БД: " + e.getMessage());
        }
    }

    private void respawnCaravanerNpc() {
        for (NpcData npc : plugin.getNpcManager().getAllNpcs()) {
            if ("caravaner".equalsIgnoreCase(npc.type())) {
                if (active) {
                    plugin.getNpcManager().spawnNpcEntity(npc);
                } else {
                    plugin.getNpcManager().ensureNpcDespawned(npc.uuid());
                }
            }
        }
    }

    @Nullable
    private Location getCaravanerNpcLocation() {
        for (NpcData npc : plugin.getNpcManager().getAllNpcs()) {
            if ("caravaner".equalsIgnoreCase(npc.type())) {
                org.bukkit.World w = Bukkit.getWorld(npc.world());
                if (w != null) {
                    return new Location(w, npc.x(), npc.y(), npc.z(), npc.yaw(), npc.pitch());
                }
            }
        }
        return null;
    }

    public void openGui(Player player) {
        if (!active) {
            MessageUtils.sendMessage(player, "<red>Караванщик сейчас не в городе!</red>");
            return;
        }
        new DailyCaravanGui(plugin, player, this).open();
    }
}
