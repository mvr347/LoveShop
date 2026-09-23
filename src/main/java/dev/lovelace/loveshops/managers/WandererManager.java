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

    public WandererManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    // ==========================================
    // SCHEDULE & ACTIVE STATE
    // ==========================================

    public boolean isWandererActive() {
        if (forceActiveOverride != null) {
            return forceActiveOverride;
        }
        if (!plugin.getConfig().getBoolean("wanderer.enabled", true)) {
            return false;
        }

        List<String> days = plugin.getConfig().getStringList("wanderer.schedule.days");
        if (days.isEmpty()) {
            days = List.of("TUESDAY", "THURSDAY", "SATURDAY");
        }
        String arrivalTime = plugin.getConfig().getString("wanderer.schedule.arrival-time", "10:00");
        String departureTime = plugin.getConfig().getString("wanderer.schedule.departure-time", "22:00");

        return TimeUtils.isScheduleTimeWindow(days, arrivalTime, departureTime);
    }

    public void checkWandererStatus() {
        boolean nowActive = isWandererActive();
        if (nowActive != active) {
            this.active = nowActive;
            if (nowActive) {
                // Wanderer arrived!
                for (String msg : plugin.getConfig().getStringList("wanderer.messages.arrival")) {
                    Bukkit.broadcast(MessageUtils.parse(msg));
                }
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

    public String getNextArrivalText() {
        List<String> days = plugin.getConfig().getStringList("wanderer.schedule.days");
        if (days.isEmpty()) days = List.of("TUESDAY", "THURSDAY", "SATURDAY");
        String arrivalTime = plugin.getConfig().getString("wanderer.schedule.arrival-time", "10:00");
        return TimeUtils.getNextScheduleArrivalText(days, arrivalTime);
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

                pool.add(new WandererItemConfig(id, material, name, lore, enchants, price, amount, weight, customModelData, itemsAdderId));
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка парсинга предмета из пула Странника: " + e.getMessage());
            }
        }

        return pool;
    }

    public List<WandererDealItem> rollRandomItems(int minItems, int maxItems) {
        List<WandererItemConfig> pool = loadItemPool();
        if (pool.isEmpty()) return Collections.emptyList();

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

            ItemStack stack = selected.buildItemStack();
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
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        int cost = plugin.getConfig().getInt("wanderer.deal.cost", 150);
        int deliveryMinutes = plugin.getConfig().getInt("wanderer.deal.delivery-time-minutes", 60);
        int minItems = plugin.getConfig().getInt("wanderer.deal.min-items", 3);
        int maxItems = plugin.getConfig().getInt("wanderer.deal.max-items", 6);
        int expireHours = plugin.getConfig().getInt("wanderer.deal.deal-expire-hours", 48);

        // Check balance on primary thread
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        String currencyName = economy != null ? economy.currencyName() : "монет";

        if (cost > 0) {
            if (economy == null || !economy.has(player, cost)) {
                String msg = plugin.getConfig().getString("wanderer.messages.insufficient-funds-deal",
                    "<red>Недостаточно монет! Требуется: <gold>{cost} {currency}</gold></red>")
                    .replace("{cost}", String.valueOf(cost))
                    .replace("{currency}", currencyName);
                MessageUtils.sendMessage(player, msg);
                future.complete(false);
                return future;
            }

            if (!economy.charge(player, cost)) {
                MessageUtils.sendMessage(player, "<red>Ошибка списания средств!</red>");
                future.complete(false);
                return future;
            }
        }

        long now = System.currentTimeMillis() / 1000;
        long readyAt = now + (deliveryMinutes * 60L);
        long expiresAt = readyAt + (expireHours * 3600L);

        List<WandererDealItem> items = rollRandomItems(minItems, maxItems);
        String itemsJson = gson.toJson(items);

        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String sql = """
                INSERT INTO wanderer_deals (player_uuid, status, ordered_at, ready_at, expires_at, items_json)
                VALUES (?, 'WAITING', ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    status='WAITING', ordered_at=EXCLUDED.ordered_at, ready_at=EXCLUDED.ready_at,
                    expires_at=EXCLUDED.expires_at, items_json=EXCLUDED.items_json
            """;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setLong(2, now);
                ps.setLong(3, readyAt);
                ps.setLong(4, expiresAt);
                ps.setString(5, itemsJson);
                ps.executeUpdate();

                WandererDeal deal = new WandererDeal(0, player.getUniqueId(), "WAITING", now, readyAt, expiresAt, items);
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
                if (cost > 0 && economy != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> economy.give(player, cost));
                }
                future.complete(false);
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> buyDealItem(Player player, String dealItemId) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        getPlayerDeal(player.getUniqueId()).thenAccept(optDeal -> {
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
                    MessageUtils.sendMessage(player, plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
                    future.complete(false);
                    return;
                }

                if (!economy.charge(player, target.price())) {
                    MessageUtils.sendMessage(player, plugin.getConfig().getString("protection.insufficient-funds", "&cНедостаточно средств!"));
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
                    dealCache.computeIfPresent(playerUuid, (k, v) -> new WandererDeal(v.id(), v.playerUuid(), "READY", v.orderedAt(), now - 5, v.expiresAt(), v.items()));
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
            items
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
