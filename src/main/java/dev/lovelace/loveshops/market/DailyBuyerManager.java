package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Buyer of the day": once a day, for a few hours, the ordinary buyer pays a bonus for the goods of
 * a couple of categories (ores, food, mob drops ...) that change every day, up to a per-player
 * limit. It reuses the buyer NPC and its price formula - the bonus is one more term - so a farmer
 * is rewarded for building a farm for what the buyer wants today.
 */
public final class DailyBuyerManager {

    private final LoveShops plugin;
    private final ZoneId zone = ZoneId.systemDefault();
    private BukkitTask task;

    /** Items each player already sold at the bonus price during {@link #usageDay}. Cleared when the day changes. */
    private final Map<UUID, Integer> used = new ConcurrentHashMap<>();
    private volatile String usageDay = "";
    private volatile boolean wasActive;
    private volatile String announcedDay = "";

    public DailyBuyerManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("market.daily-buyer");
    }

    public boolean enabled() {
        ConfigurationSection s = cfg();
        return s != null && s.getBoolean("enabled", true);
    }

    private int startHour() { ConfigurationSection s = cfg(); return s == null ? 18 : s.getInt("start-hour", 18); }
    private int durationHours() { ConfigurationSection s = cfg(); return s == null ? 6 : s.getInt("duration-hours", 6); }
    private int categoriesPerDay() { ConfigurationSection s = cfg(); return s == null ? 2 : Math.max(1, s.getInt("categories-per-day", 2)); }
    private int bonusPercent() { ConfigurationSection s = cfg(); return s == null ? 15 : Math.max(0, s.getInt("bonus-percent", 15)); }
    private int limitPerPlayer() { ConfigurationSection s = cfg(); return s == null ? 256 : Math.max(1, s.getInt("limit-per-player", 256)); }

    public void start() {
        if (!enabled()) return;
        // Every 30 s: announce the window opening and closing, and drop yesterday's counters.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 600L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        used.clear();
    }

    // ------------------------------------------------------------------ time and categories

    /** The key of the current buyer "day": it changes when the window opens, so a window never straddles two. */
    public String dayKey() {
        return RobberyMath.dayKey(System.currentTimeMillis(), startHour(), zone);
    }

    public boolean isActive() {
        if (!enabled()) return false;
        ZonedDateTime now = ZonedDateTime.now(zone);
        return DailyBuyerMath.isActive(now.getHour() * 60 + now.getMinute(), startHour(), durationHours());
    }

    /** Category names configured under {@code market.daily-buyer.categories}. */
    private Map<String, List<String>> allCategories() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        ConfigurationSection s = cfg();
        if (s == null) return out;
        ConfigurationSection cats = s.getConfigurationSection("categories");
        if (cats == null) return out;
        for (String key : cats.getKeys(false)) out.put(key, cats.getStringList(key + ".materials"));
        return out;
    }

    public List<String> todaysCategories() {
        return DailyBuyerMath.pickCategories(dayKey(), new ArrayList<>(allCategories().keySet()), categoriesPerDay());
    }

    /** Human-readable titles of today's categories. */
    public List<String> todaysTitles() {
        ConfigurationSection s = cfg();
        List<String> titles = new ArrayList<>();
        for (String key : todaysCategories()) {
            titles.add(s == null ? key : s.getString("categories." + key + ".title", key));
        }
        return titles;
    }

    private Set<Material> todaysMaterials() {
        Set<Material> out = EnumSet.noneOf(Material.class);
        Map<String, List<String>> all = allCategories();
        for (String cat : todaysCategories()) {
            for (String name : all.getOrDefault(cat, List.of())) {
                Material m = Material.matchMaterial(name);
                if (m != null) out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ price hook

    /** Bonus percent added to the buyer's price for this player and item right now (0 = none). */
    public int bonusFor(Player player, Material material) {
        if (!isActive() || material == null) return 0;
        if (!todaysMaterials().contains(material)) return 0;
        if (remaining(player.getUniqueId()) <= 0) return 0;
        return bonusPercent();
    }

    /** Called after a sale went through: counts the items against the player's daily limit. */
    public void consume(Player player, Material material, int quantity) {
        if (!isActive() || material == null || !todaysMaterials().contains(material)) return;
        rollDayIfNeeded();
        UUID id = player.getUniqueId();
        int before = used.computeIfAbsent(id, this::loadUsed);
        int add = Math.min(Math.max(0, quantity), DailyBuyerMath.remainingAllowance(limitPerPlayer(), before));
        if (add <= 0) return;
        int now = before + add;
        used.put(id, now);
        String day = usageDay;
        Bukkit.getAsyncScheduler().runNow(plugin, t -> saveUsed(id, day, now));
    }

    public int remaining(UUID player) {
        rollDayIfNeeded();
        return DailyBuyerMath.remainingAllowance(limitPerPlayer(), used.computeIfAbsent(player, this::loadUsed));
    }

    private void rollDayIfNeeded() {
        String day = dayKey();
        if (!day.equals(usageDay)) {
            synchronized (this) {
                if (!day.equals(usageDay)) {
                    used.clear();
                    usageDay = day;
                }
            }
        }
    }

    private int loadUsed(UUID player) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT items FROM daily_buyer_usage WHERE player_uuid = ? AND day_key = ?")) {
            ps.setString(1, player.toString());
            ps.setString(2, usageDay);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Лимит скупщика дня игрока " + player + " не прочитан: " + e.getMessage());
            return 0;
        }
    }

    private void saveUsed(UUID player, String day, int items) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO daily_buyer_usage (player_uuid, day_key, items) VALUES (?,?,?) "
                             + "ON CONFLICT(player_uuid, day_key) DO UPDATE SET items = excluded.items")) {
            ps.setString(1, player.toString());
            ps.setString(2, day);
            ps.setInt(3, items);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("Лимит скупщика дня игрока " + player + " не записан: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ announcements

    private void tick() {
        rollDayIfNeeded();
        boolean active = isActive();
        String day = dayKey();
        if (active && !wasActive && !day.equals(announcedDay)) {
            announcedDay = day;
            String titles = String.join("<gray>, </gray>", todaysTitles().stream().map(t -> "<gold>" + t + "</gold>").toList());
            Bukkit.broadcast(MessageUtils.parse(plugin.getLangManager().getRaw("prefix", "")
                    + plugin.getMarketMessages().raw("daily-buyer-start", "categories", titles,
                    "bonus", String.valueOf(bonusPercent()), "hours", String.valueOf(durationHours()))));
        } else if (!active && wasActive) {
            Bukkit.broadcast(MessageUtils.parse(plugin.getLangManager().getRaw("prefix", "")
                    + plugin.getMarketMessages().raw("daily-buyer-end")));
        }
        wasActive = active;
        // Old usage rows are only history.
        if (Bukkit.getCurrentTick() % 12000 < 600) pruneUsage();
    }

    private void pruneUsage() {
        String keep = dayKey();
        Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM daily_buyer_usage WHERE day_key < ?")) {
                ps.setString(1, keep);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Очистка лимитов скупщика дня не удалась: " + e.getMessage());
            }
        });
    }
}
