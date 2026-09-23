package dev.lovelace.loveshops.managers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.lovecore.api.social.BehaviorLevels;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.models.WandererDeal;
import dev.lovelace.loveshops.models.WandererDealItem;
import dev.lovelace.loveshops.models.WandererItemConfig;
import dev.lovelace.loveshops.models.WandererItemQuality;
import dev.lovelace.loveshops.models.WandererRequestCategory;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import dev.lovelace.loveshops.utils.MessageUtils;
import dev.lovelace.loveshops.utils.TimeUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class WandererManager {

    private final LoveShops plugin;
    private final Gson gson = new Gson();
    private final Type itemsListType = new TypeToken<List<WandererDealItem>>() {}.getType();
    private final Random random = new Random();

    private boolean active = false;
    private Boolean forceActiveOverride = null;
    private final Map<UUID, WandererDeal> dealCache = new ConcurrentHashMap<>();

    // Randomized arrival schedule (см. isTodayArrivalDay) - персистится, чтобы "не два дня
    // подряд" переживало рестарт сервера, а не пересчитывалось с чистого листа. decision
    // кэшируется на день, чтобы не переигрывать бросок кубика на каждый вызов
    // isWandererActive() (checkWandererStatus дёргает его раз в 30с).
    private LocalDate lastArrivalDate;
    private LocalDate cachedDecisionDate;
    private boolean cachedTodayIsArrivalDay;

    // Гвард от повторного входа в buyDealItem, тот же паттерн, что и
    // BuyerManager.processingSales: getPlayerDeal() читает состояние из БД асинхронно,
    // а сама покупка ещё раз прыгает на главный поток и обратно в БД. Без гварда два
    // быстрых клика по одному товару (или клик, повторённый до того как GUI успел
    // перерисоваться) запускают buyDealItem дважды параллельно — оба читают ОДИН и тот же
    // "ещё не куплен" снимок сделки до того, как первый успевает записать items_json,
    // и оба списывают деньги и выдают предмет: лимитированный товар Странника продаётся
    // дважды. Один "в процессе" слот на игрока за раз.
    private final Set<UUID> processingPurchases = ConcurrentHashMap.newKeySet();

    public WandererManager(LoveShops plugin) {
        this.plugin = plugin;
        loadScheduleState();
    }

    // ==========================================
    // SCHEDULE & ACTIVE STATE
    // ==========================================

    private void loadScheduleState() {
        // Blocking on purpose - runs once, synchronously, from the constructor during
        // LoveShops#onEnable, same idiom DatabaseManager.initialize() already uses there
        // (local SQLite, negligible cost at startup).
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT last_arrival_date FROM wanderer_schedule_state WHERE id = 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String raw = rs.getString("last_arrival_date");
                if (raw != null) {
                    try {
                        lastArrivalDate = LocalDate.parse(raw);
                    } catch (Exception ignored) {}
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Не удалось загрузить состояние расписания Странника: " + e.getMessage());
        }
    }

    private void persistLastArrivalDate(LocalDate date) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO wanderer_schedule_state (id, last_arrival_date) VALUES (1, ?)
                ON CONFLICT(id) DO UPDATE SET last_arrival_date = EXCLUDED.last_arrival_date
            """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, date.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Не удалось сохранить дату визита Странника: " + e.getMessage());
            }
        });
    }

    /**
     * Random arrival days instead of a fixed weekly list, with one hard rule: never two days
     * in a row (track {@link #lastArrivalDate} and exclude "today" from the roll whenever
     * yesterday was already an arrival day). The decision is made once per calendar day and
     * cached — {@link #isWandererActive()} (and therefore this) is polled every ~30s by
     * {@code ScheduleListener}.
     */
    private boolean isTodayArrivalDay() {
        LocalDate today = LocalDate.now();
        if (today.equals(cachedDecisionDate)) {
            return cachedTodayIsArrivalDay;
        }

        boolean decision;
        if (today.equals(lastArrivalDate)) {
            // Already rolled "yes" for today earlier (e.g. before a restart mid-day) - stay
            // consistent instead of re-rolling and possibly flipping to "no" partway through
            // the day the Wanderer already arrived on.
            decision = true;
        } else if (today.minusDays(1).equals(lastArrivalDate)) {
            // Никогда два дня подряд.
            decision = false;
        } else {
            double arrivalsPerWeek = plugin.getConfig().getDouble("wanderer.schedule.arrivals-per-week", 3.0);
            double probability = Math.max(0.0, Math.min(1.0, arrivalsPerWeek / 7.0));
            decision = random.nextDouble() < probability;
        }

        cachedDecisionDate = today;
        cachedTodayIsArrivalDay = decision;

        if (decision && !today.equals(lastArrivalDate)) {
            lastArrivalDate = today;
            persistLastArrivalDate(today);
        }

        return decision;
    }

    public boolean isWandererActive() {
        if (forceActiveOverride != null) {
            return forceActiveOverride;
        }
        if (!plugin.getConfig().getBoolean("wanderer.enabled", true)) {
            return false;
        }
        if (!isTodayArrivalDay()) {
            return false;
        }

        String arrivalTime = plugin.getConfig().getString("wanderer.schedule.arrival-time", "10:00");
        String departureTime = plugin.getConfig().getString("wanderer.schedule.departure-time", "22:00");
        return TimeUtils.isScheduleTimeWindow(List.of(LocalDate.now().getDayOfWeek().name()), arrivalTime, departureTime);
    }

    public void checkWandererStatus() {
        // Всё ниже — Bukkit.broadcast/getOnlinePlayers/NPC spawn — только с главного потока;
        // ScheduleListener дёргает этот метод прямо с Bukkit.getAsyncScheduler(), тот же
        // самогвард, что и в SellerManager.checkSellerStatus.
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::checkWandererStatus);
            return;
        }

        boolean nowActive = isWandererActive();
        if (nowActive != active) {
            this.active = nowActive;
            if (nowActive) {
                // Странник пришёл - НИКАКОГО общего оповещения (убрано по требованию: раньше
                // здесь был Bukkit.broadcast по wanderer.messages.arrival). Вместо этого —
                // приватный, предвзятый по стилю игры "визит" отдельным игрокам, см.
                // sendTargetedVisitNotices().
                sendTargetedVisitNotices();
                for (var npc : plugin.getNpcManager().getNpcsByType("wanderer")) {
                    plugin.getNpcManager().spawnNpcEntity(npc);
                }
            } else {
                // Wanderer departed!
                for (String msg : plugin.getConfig().getStringList("wanderer.messages.departure")) {
                    Bukkit.broadcast(MessageUtils.parse(msg));
                }
                for (var npc : plugin.getNpcManager().getNpcsByType("wanderer")) {
                    plugin.getNpcManager().despawnNpcEntity(npc.uuid());
                }
            }
        }
    }

    /**
     * Replaces the removed server-wide "Странник пришёл" broadcast: instead of announcing to
     * everyone, the Wanderer quietly "visits" (privately messages) a biased subset of online
     * players. Prefers players whose LoveBehavior playstyle is classified as NOT aggressive
     * (LoveCore.BehaviorLevels#playstyleLevel above {@code aggressive-threshold} - see
     * BehaviorLevels: 0 = maximally aggressive, MAX_LEVEL = maximally kind); a "конфликтный"
     * (aggressive) player still gets a chance ({@code aggressive-consider-chance}), just a
     * reduced one. This is a targeting/visibility bias only — it does NOT gate whether a
     * player can actually trade with the Wanderer once they walk up (that's
     * {@link #isPlayerEligible}, a separate, pre-existing mechanic) and is unrelated to the
     * "aggressive playstyle unlocks secret deals" buff LoveBehavior implements on its own side.
     */
    private void sendTargetedVisitNotices() {
        if (!plugin.getConfig().getBoolean("wanderer.visit-targeting.enabled", true)) {
            return;
        }

        int aggressiveThreshold = plugin.getConfig().getInt("wanderer.visit-targeting.aggressive-threshold", 2);
        double aggressiveConsiderChance = plugin.getConfig().getDouble("wanderer.visit-targeting.aggressive-consider-chance", 0.5);

        List<String> messages = plugin.getConfig().getStringList("wanderer.visit-targeting.messages");
        if (messages.isEmpty()) {
            messages = List.of("<dark_purple>[Странник]</dark_purple> <white>Кто-то тихо стучит в дверь твоего дома... Таинственный Странник заглянул в город и ищет встречи именно с тобой.</white>");
        }

        Optional<BehaviorLevels> levels = LoveCore.service(BehaviorLevels.class);
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean aggressive = levels.map(l -> l.playstyleLevel(player.getUniqueId()) <= aggressiveThreshold).orElse(false);
            boolean visited = !aggressive || random.nextDouble() < aggressiveConsiderChance;
            if (!visited) continue;

            String chosen = messages.get(random.nextInt(messages.size()));
            MessageUtils.sendMessage(player, chosen);
        }
    }

    public void forceStartWanderer() {
        this.forceActiveOverride = true;
        this.active = false;
        checkWandererStatus();
    }

    public void forceStopWanderer() {
        this.forceActiveOverride = false;
        this.active = true;
        checkWandererStatus();
    }

    public void resetWandererOverride() {
        this.forceActiveOverride = null;
        boolean oldState = this.active;
        this.active = !oldState;
        checkWandererStatus();
    }

    public Boolean getForceActiveOverride() {
        return forceActiveOverride;
    }

    /**
     * With a randomized day-by-day schedule (see {@link #isTodayArrivalDay}) future arrival
     * days genuinely aren't decided yet, so this can only speak to today at best - it no
     * longer promises a specific future date the way the old fixed weekly list did.
     */
    public String getNextArrivalText() {
        String arrivalTime = plugin.getConfig().getString("wanderer.schedule.arrival-time", "10:00");
        if (isTodayArrivalDay()) {
            LocalTime start = LocalTime.parse(arrivalTime, DateTimeFormatter.ofPattern("HH:mm"));
            if (LocalTime.now().isBefore(start)) {
                return "Сегодня в " + arrivalTime;
            }
            String departureTime = plugin.getConfig().getString("wanderer.schedule.departure-time", "22:00");
            LocalTime end = LocalTime.parse(departureTime, DateTimeFormatter.ofPattern("HH:mm"));
            if (!LocalTime.now().isAfter(end)) {
                return "Уже в городе!";
            }
        }
        double arrivalsPerWeek = plugin.getConfig().getDouble("wanderer.schedule.arrivals-per-week", 3.0);
        return "Расписание случайное (~" + arrivalsPerWeek + " раз(а) в неделю) — загляните завтра в " + arrivalTime;
    }

    // ==========================================
    // LOVEBEHAVIOR & PLAY-STYLE CHECK
    // ==========================================

    public CompletableFuture<Boolean> isPlayerEligible(Player player) {
        if (!plugin.getConfig().getBoolean("wanderer.playstyle-requirement.enabled", true)) {
            return CompletableFuture.completedFuture(true);
        }

        if (player.hasPermission("loveshops.wanderer.bypass") || player.hasPermission("loveshops.admin")) {
            return CompletableFuture.completedFuture(true);
        }

        int threshold = plugin.getConfig().getInt("wanderer.playstyle-requirement.playstyle-threshold",
                plugin.getConfig().getInt("wanderer.playstyle-threshold", 0));
        boolean levelCheck = LoveCore.service(BehaviorLevels.class)
                .map(levels -> levels.playstyleLevel(player.getUniqueId()) <= threshold)
                .orElse(false);
        if (levelCheck) {
            return CompletableFuture.completedFuture(true);
        }

        String targetStyle = plugin.getConfig().getString("wanderer.playstyle-requirement.required-style", "aggressive");

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // 1. Check local reputation / status override in SQLite
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT status FROM buyer_reputation_overrides WHERE player_uuid = ?")) {
                ps.setString(1, player.getUniqueId().toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String localStatus = rs.getString("status");
                    if (targetStyle.equalsIgnoreCase(localStatus)) {
                        future.complete(true);
                        return;
                    }
                }
            } catch (SQLException ignored) {}

            // 2. Check LoveBehavior API via Bukkit ServicesManager
            try {
                Class<?> apiClass = Class.forName("dev.lovelace.lovebehavior.api.LoveBehaviorAPI");
                Object apiInstance = Bukkit.getServicesManager().load(apiClass);
                if (apiInstance != null) {
                    for (String methodName : List.of("getPlayStyle", "getPlaystyle", "getPlayerStyle", "getStyle", "getReputationLevel", "getReputation")) {
                        try {
                            var m = apiClass.getMethod(methodName, UUID.class);
                            Object res = m.invoke(apiInstance, player.getUniqueId());
                            if (res != null && targetStyle.equalsIgnoreCase(String.valueOf(res))) {
                                future.complete(true);
                                return;
                            }
                        } catch (NoSuchMethodException ignored) {}
                        try {
                            var m = apiClass.getMethod(methodName, Player.class);
                            Object res = m.invoke(apiInstance, player);
                            if (res != null && targetStyle.equalsIgnoreCase(String.valueOf(res))) {
                                future.complete(true);
                                return;
                            }
                        } catch (NoSuchMethodException ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            // 3. Check PlaceholderAPI if present
            if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                try {
                    String papiValue = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, "%lovebehavior_playstyle%");
                    if (targetStyle.equalsIgnoreCase(papiValue)) {
                        future.complete(true);
                        return;
                    }
                } catch (Exception ignored) {}
            }

            future.complete(false);
        });

        return future;
    }

    public void rejectPlayer(Player player) {
        List<String> messages = plugin.getConfig().getStringList("wanderer.messages.not-aggressive");
        if (messages.isEmpty()) {
            messages = List.of("<red>Странник:</red> <gray>Я веду дела только с игроками агрессивного стиля!</gray>");
        }
        String chosen = messages.get(random.nextInt(messages.size()));
        MessageUtils.sendMessage(player, chosen);
    }

    // ==========================================
    // ITEMS POOL LOADING & ROLLING
    // ==========================================

    public List<WandererItemConfig> loadItemPool() {
        List<WandererItemConfig> pool = new ArrayList<>();
        List<Map<?, ?>> rawList = plugin.getConfig().getMapList("wanderer.items-pool");

        for (Map<?, ?> map : rawList) {
            try {
                String id = map.get("id") != null ? String.valueOf(map.get("id")) : UUID.randomUUID().toString();
                String material = map.get("material") != null ? String.valueOf(map.get("material")) : "STONE";
                String name = map.containsKey("name") ? String.valueOf(map.get("name")) : null;

                List<String> lore = null;
                if (map.containsKey("lore") && map.get("lore") instanceof List<?> l) {
                    lore = l.stream().map(String::valueOf).toList();
                }

                Map<String, Integer> enchants = null;
                if (map.containsKey("enchants") && map.get("enchants") instanceof Map<?, ?> em) {
                    enchants = new HashMap<>();
                    for (Map.Entry<?, ?> e : em.entrySet()) {
                        enchants.put(String.valueOf(e.getKey()), Integer.parseInt(String.valueOf(e.getValue())));
                    }
                }

                int price = map.get("price") != null ? Integer.parseInt(String.valueOf(map.get("price"))) : 100;
                int amount = map.get("amount") != null ? Integer.parseInt(String.valueOf(map.get("amount"))) : 1;
                int weight = map.get("weight") != null ? Integer.parseInt(String.valueOf(map.get("weight"))) : 10;
                Integer customModelData = map.containsKey("custom-model-data") ? Integer.parseInt(String.valueOf(map.get("custom-model-data"))) : null;
                String itemsAdderId = map.containsKey("itemsadder-id") ? String.valueOf(map.get("itemsadder-id")) : null;
                String category = map.get("category") != null ? String.valueOf(map.get("category")) : null;

                pool.add(new WandererItemConfig(id, material, name, lore, enchants, price, amount, weight, customModelData, itemsAdderId, category));
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка парсинга предмета из пула Странника: " + e.getMessage());
            }
        }

        return pool;
    }

    public List<WandererDealItem> rollRandomItems(int minItems, int maxItems) {
        return rollRandomItems(minItems, maxItems, null);
    }

    /**
     * @param requestedCategory when non-null, only pool entries tagged with this category are
     *                          eligible (paid personal request — see {@code wanderer.deal.personal-request}).
     *                          Falls back to the full pool (with a warning) if that category is
     *                          empty in {@code wanderer.items-pool}, so a misconfigured category
     *                          never silently hands back zero items after the player already paid.
     */
    public List<WandererDealItem> rollRandomItems(int minItems, int maxItems, WandererRequestCategory requestedCategory) {
        List<WandererItemConfig> pool = loadItemPool();
        if (pool.isEmpty()) return Collections.emptyList();

        if (requestedCategory != null) {
            List<WandererItemConfig> filtered = pool.stream()
                .filter(item -> item.resolvedCategory() == requestedCategory)
                .toList();
            if (filtered.isEmpty()) {
                plugin.getLogger().warning("Странник: в wanderer.items-pool нет предметов категории "
                    + requestedCategory + " — заказ будет собран из общего пула.");
            } else {
                pool = filtered;
            }
        }

        WandererItemQuality quality = WandererItemQuality.fromConfig(plugin.getConfig());

        int targetCount = minItems + (maxItems > minItems ? random.nextInt(maxItems - minItems + 1) : 0);
        int totalWeight = pool.stream().mapToInt(WandererItemConfig::weight).sum();
        if (totalWeight <= 0) totalWeight = pool.size();

        List<WandererDealItem> result = new ArrayList<>();
        List<WandererItemConfig> poolCopy = new ArrayList<>(pool);

        for (int i = 0; i < targetCount && !poolCopy.isEmpty(); i++) {
            int roll = random.nextInt(totalWeight);
            int cumulative = 0;
            WandererItemConfig selected = poolCopy.get(0);

            for (WandererItemConfig item : poolCopy) {
                cumulative += item.weight();
                if (roll < cumulative) {
                    selected = item;
                    break;
                }
            }

            ItemStack stack = selected.buildItemStack(quality, random);
            String base64 = ItemStackConverter.itemStackToBase64(stack);
            String dealItemId = UUID.randomUUID().toString().substring(0, 8);

            result.add(new WandererDealItem(
                dealItemId,
                selected.name() != null ? selected.name() : selected.material(),
                selected.price(),
                selected.amount(),
                false,
                base64
            ));

            // Prevent immediate duplicates if pool is large enough
            if (poolCopy.size() > targetCount) {
                totalWeight -= selected.weight();
                poolCopy.remove(selected);
                if (totalWeight <= 0) totalWeight = 1;
            }
        }

        return result;
    }

    // ==========================================
    // DEAL OPERATIONS & PERSISTENCE
    // ==========================================

    public CompletableFuture<Optional<WandererDeal>> getPlayerDeal(UUID playerUuid) {
        CompletableFuture<Optional<WandererDeal>> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = "SELECT * FROM wanderer_deals WHERE player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    WandererDeal deal = mapDeal(rs);
                    long now = System.currentTimeMillis() / 1000;

                    // Auto-update expired deal
                    if (deal.expiresAt() > 0 && now > deal.expiresAt() && !deal.status().equalsIgnoreCase("COMPLETED")) {
                        deal = deal.withStatus("EXPIRED");
                        updateDealStatus(playerUuid, "EXPIRED");
                    } else if (deal.isReady() && deal.status().equalsIgnoreCase("WAITING")) {
                        deal = deal.withStatus("READY");
                        updateDealStatus(playerUuid, "READY");
                    }

                    dealCache.put(playerUuid, deal);
                    future.complete(Optional.of(deal));
                } else {
                    dealCache.remove(playerUuid);
                    future.complete(Optional.empty());
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка загрузки сделки Странника: " + e.getMessage());
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> startDeal(Player player) {
        return startDeal(player, null);
    }

    /**
     * @param requestedCategory non-null means the player is paying extra
     *                          ({@code wanderer.deal.personal-request.surcharge-percent}) to
     *                          have the Wanderer's incoming delivery drawn only from that
     *                          category of {@code wanderer.items-pool} instead of the full pool.
     */
    public CompletableFuture<Boolean> startDeal(Player player, WandererRequestCategory requestedCategory) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        int baseCost = plugin.getConfig().getInt("wanderer.deal.cost", 150);
        int cost = baseCost;
        boolean personalRequestEnabled = plugin.getConfig().getBoolean("wanderer.deal.personal-request.enabled", true);
        if (requestedCategory != null && personalRequestEnabled) {
            double surchargePercent = plugin.getConfig().getDouble("wanderer.deal.personal-request.surcharge-percent", 50);
            cost = (int) Math.round(baseCost * (1 + surchargePercent / 100.0));
        } else if (requestedCategory != null) {
            // personal-request disabled server-side — fall back to a regular unfiltered deal
            // rather than silently charging a surcharge for a feature that's off.
            requestedCategory = null;
        }
        final WandererRequestCategory finalCategory = requestedCategory;
        final int finalCost = cost;

        int deliveryMinutes = plugin.getConfig().getInt("wanderer.deal.delivery-time-minutes", 60);
        int minItems = plugin.getConfig().getInt("wanderer.deal.min-items", 3);
        int maxItems = plugin.getConfig().getInt("wanderer.deal.max-items", 6);
        int expireHours = plugin.getConfig().getInt("wanderer.deal.deal-expire-hours", 48);

        // Check balance on primary thread
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        String currencyName = economy != null ? economy.currencyName() : "монет";

        if (finalCost > 0) {
            if (economy == null || !economy.has(player, finalCost)) {
                String msg = plugin.getConfig().getString("wanderer.messages.insufficient-funds-deal",
                    "<red>Недостаточно монет! Требуется: <gold>{currency_icon}{cost} {currency}</gold></red>")
                    .replace("{cost}", String.valueOf(finalCost))
                    .replace("{currency}", currencyName)
                    .replace("{currency_icon}", MessageUtils.currencyIcon());
                MessageUtils.sendMessage(player, msg);
                future.complete(false);
                return future;
            }

            if (!economy.charge(player, finalCost)) {
                MessageUtils.sendMessage(player, "<red>Ошибка списания средств!</red>");
                future.complete(false);
                return future;
            }
        }

        long now = System.currentTimeMillis() / 1000;
        long readyAt = now + (deliveryMinutes * 60L);
        long expiresAt = readyAt + (expireHours * 3600L);

        List<WandererDealItem> items = rollRandomItems(minItems, maxItems, finalCategory);
        String itemsJson = gson.toJson(items);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO wanderer_deals (player_uuid, status, ordered_at, ready_at, expires_at, items_json, requested_category)
                VALUES (?, 'WAITING', ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    status='WAITING', ordered_at=EXCLUDED.ordered_at, ready_at=EXCLUDED.ready_at,
                    expires_at=EXCLUDED.expires_at, items_json=EXCLUDED.items_json, requested_category=EXCLUDED.requested_category
            """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setLong(2, now);
                ps.setLong(3, readyAt);
                ps.setLong(4, expiresAt);
                ps.setString(5, itemsJson);
                if (finalCategory != null) {
                    ps.setString(6, finalCategory.name());
                } else {
                    ps.setNull(6, java.sql.Types.VARCHAR);
                }
                ps.executeUpdate();

                WandererDeal deal = new WandererDeal(0, player.getUniqueId(), "WAITING", now, readyAt, expiresAt, items, finalCategory != null ? finalCategory.name() : null);
                dealCache.put(player.getUniqueId(), deal);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    String timeText = TimeUtils.formatRemainingTime(deliveryMinutes * 60L);
                    String startedMsg = plugin.getConfig().getString("wanderer.messages.deal-started",
                        "<green>Странник:</green> <gray>Договорились! Я принесу товар через <gold>{time}</gold>.</gray>")
                        .replace("{time}", timeText);
                    MessageUtils.sendMessage(player, startedMsg);
                    future.complete(true);
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка создания сделки Странника: " + e.getMessage());
                // Refund if failed
                if (finalCost > 0 && economy != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> economy.give(player, finalCost));
                }
                future.complete(false);
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> buyDealItem(Player player, String dealItemId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        UUID playerUuid = player.getUniqueId();

        if (!processingPurchases.add(playerUuid)) {
            // Уже есть покупка в процессе у этого игрока — второй (параллельный или
            // просто слишком быстрый) клик игнорируем вместо того, чтобы дать ему тоже
            // дойти до выдачи предмета (см. комментарий у processingPurchases).
            future.complete(false);
            return future;
        }
        future.whenComplete((result, error) -> processingPurchases.remove(playerUuid));

        getPlayerDeal(playerUuid).thenAccept(optDeal -> {
            if (optDeal.isEmpty()) {
                future.complete(false);
                return;
            }

            WandererDeal deal = optDeal.get();
            if (!deal.isReady() || deal.isExpired()) {
                future.complete(false);
                return;
            }

            WandererDealItem target = deal.items().stream()
                .filter(i -> i.id().equals(dealItemId) && !i.bought())
                .findFirst().orElse(null);

            if (target == null) {
                future.complete(false);
                return;
            }

            ItemStack itemStack = ItemStackConverter.itemStackFromBase64(target.itemDataBase64());
            if (itemStack == null) {
                future.complete(false);
                return;
            }

            // Primary thread balance and inventory checks
            Bukkit.getScheduler().runTask(plugin, () -> {
                LoveEconomy economy = plugin.getEconomy().orElse(null);
                if (economy == null || !economy.has(player, target.price())) {
                    MessageUtils.sendMessage(player, MessageUtils.currencyIcon() + plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
                    future.complete(false);
                    return;
                }

                if (!economy.charge(player, target.price())) {
                    MessageUtils.sendMessage(player, MessageUtils.currencyIcon() + plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
                    future.complete(false);
                    return;
                }

                // Give item (drop any overflow)
                for (ItemStack extra : player.getInventory().addItem(itemStack).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), extra);
                }
                MessageUtils.sendMessage(player, "<green>Вы успешно приобрели товар у Странника!</green>");

                // Update deal items list
                List<WandererDealItem> updatedItems = new ArrayList<>();
                for (WandererDealItem it : deal.items()) {
                    if (it.id().equals(dealItemId)) {
                        updatedItems.add(it.withBought(true));
                    } else {
                        updatedItems.add(it);
                    }
                }

                WandererDeal updatedDeal = deal.withItems(updatedItems);
                dealCache.put(player.getUniqueId(), updatedDeal);

                // Save to database async
                Bukkit.getAsyncScheduler().runNow(plugin, saveTask -> {
                    String updateSql = "UPDATE wanderer_deals SET items_json = ? WHERE player_uuid = ?";
                    try (Connection conn = plugin.getDatabaseManager().getConnection();
                         PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        ps.setString(1, gson.toJson(updatedItems));
                        ps.setString(2, player.getUniqueId().toString());
                        ps.executeUpdate();
                        future.complete(true);
                    } catch (SQLException e) {
                        plugin.getLogger().severe("Ошибка обновления купленного предмета: " + e.getMessage());
                        future.complete(true);
                    }
                });
            });
        });

        return future;
    }

    public CompletableFuture<Void> resetDeal(UUID playerUuid) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        dealCache.remove(playerUuid);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = "DELETE FROM wanderer_deals WHERE player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerUuid.toString());
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка сброса сделки Странника: " + e.getMessage());
                future.complete(null);
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> setDealReady(UUID playerUuid) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            long now = System.currentTimeMillis() / 1000;
            String sql = "UPDATE wanderer_deals SET ready_at = ?, status = 'READY' WHERE player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, now - 5);
                ps.setString(2, playerUuid.toString());
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    dealCache.computeIfPresent(playerUuid, (k, v) -> new WandererDeal(v.id(), v.playerUuid(), "READY", v.orderedAt(), now - 5, v.expiresAt(), v.items(), v.requestedCategory()));
                    future.complete(true);
                } else {
                    future.complete(false);
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Ошибка ускорения сделки: " + e.getMessage());
                future.complete(false);
            }
        });

        return future;
    }

    private void updateDealStatus(UUID playerUuid, String status) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = "UPDATE wanderer_deals SET status = ? WHERE player_uuid = ?";
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        });
    }

    private WandererDeal mapDeal(ResultSet rs) throws SQLException {
        String json = rs.getString("items_json");
        List<WandererDealItem> items = Collections.emptyList();
        if (json != null && !json.isEmpty()) {
            try {
                items = gson.fromJson(json, itemsListType);
            } catch (Exception ignored) {}
        }

        return new WandererDeal(
            rs.getInt("id"),
            UUID.fromString(rs.getString("player_uuid")),
            rs.getString("status"),
            rs.getLong("ordered_at"),
            rs.getLong("ready_at"),
            rs.getLong("expires_at"),
            items,
            rs.getString("requested_category")
        );
    }

    public void handleWandererInteraction(Player player) {
        if (!isWandererActive()) {
            List<String> msgs = plugin.getConfig().getStringList("wanderer.messages.not-active");
            if (msgs.isEmpty()) {
                msgs = List.of("<dark_purple>Странник:</dark_purple> <gray>Сейчас я в дальнем пути. Я бываю в городе только 3 раза в неделю!</gray>");
            }
            MessageUtils.sendMessage(player, msgs.get(0));
            return;
        }

        isPlayerEligible(player).thenAccept(eligible -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!eligible) {
                    rejectPlayer(player);
                    return;
                }

                getPlayerDeal(player.getUniqueId()).thenAccept(optDeal -> {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (optDeal.isEmpty() || optDeal.get().status().equalsIgnoreCase("COMPLETED") || optDeal.get().isExpired()) {
                            new dev.lovelace.loveshops.gui.WandererDealGui(plugin, player).open();
                        } else {
                            WandererDeal deal = optDeal.get();
                            if (deal.isReady()) {
                                new dev.lovelace.loveshops.gui.WandererShopGui(plugin, player, deal).open();
                            } else {
                                new dev.lovelace.loveshops.gui.WandererWaitingGui(plugin, player, deal).open();
                            }
                        }
                    });
                });
            });
        });
    }
}
