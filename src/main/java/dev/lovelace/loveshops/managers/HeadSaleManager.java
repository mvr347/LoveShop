package dev.lovelace.loveshops.managers;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.lovecore.api.social.BehaviorLevels;
import dev.lovelace.lovecore.api.social.ReputationOracle;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.managers.HeadPriceRules.Params;
import dev.lovelace.loveshops.managers.HeadPriceRules.Subject;
import dev.lovelace.loveshops.utils.Money;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Wanderer buys heads that LoveHunt hands to the customer of a finished hunt. The head is recognised by the
 * {@code lovehunt:delivered_head} item tag (it holds the victim's UUID), so the two plugins do not depend on each
 * other. The price comes from what the head's owner is: play time, kills, reputation and playstyle.
 *
 * <p>A head of the same player can be sold once per cooldown: without it a pair of friends could order hunts on
 * each other and live off the Wanderer. The last-sale times are kept in memory (loaded at startup, written through
 * to the database), so the check and the sale are one step on the main thread.
 */
public final class HeadSaleManager {
    public static final NamespacedKey HEAD_KEY = Objects.requireNonNull(NamespacedKey.fromString("lovehunt:delivered_head"));

    public static final NamespacedKey DETAILS_KEY = Objects.requireNonNull(NamespacedKey.fromString("lovehunt:head_details"));

    public enum Result { SOLD, NO_HEAD, COOLDOWN, NO_ECONOMY, DISABLED }

    /** A priced head: what the Wanderer would pay for it. */
    public record Quote(UUID victim, String name, long price, long cooldownLeftMillis) {
    }

    public record Sale(Result result, Quote quote) {
    }

    private final LoveShops plugin;
    private final Map<UUID, Long> lastSold = new ConcurrentHashMap<>();

    public HeadSaleManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    /** Creates the table if needed and loads the sales that are still on cooldown. */
    public void load() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection connection = plugin.getDatabaseManager().getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                        CREATE TABLE IF NOT EXISTS wanderer_head_sales (
                            victim_uuid TEXT PRIMARY KEY NOT NULL,
                            seller_uuid TEXT NOT NULL,
                            price INTEGER NOT NULL,
                            sold_at INTEGER NOT NULL
                        );
                    """);
                }
                long since = System.currentTimeMillis() - cooldownMillis();
                try (PreparedStatement ps = connection.prepareStatement("SELECT victim_uuid, sold_at FROM wanderer_head_sales WHERE sold_at > ?")) {
                    ps.setLong(1, since);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            lastSold.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Странник: не удалось подготовить продажи голов: " + e.getMessage());
            }
        });
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("wanderer.head-sale.enabled", true);
    }

    // ---- heads in the inventory ------------------------------------------------------------------------------------

    /** How the player died and how long he was hunted, from the tag LoveHunt wrote on the head; null for an old head. */
    public static HeadPriceRules.Details detailsOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return HeadPriceRules.Details.parse(item.getItemMeta().getPersistentDataContainer().get(DETAILS_KEY, PersistentDataType.STRING));
    }

    /** The victim a delivered head belongs to, or {@code null} when the item is not one. */
    public static UUID victimOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(HEAD_KEY, PersistentDataType.STRING);
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static int countHeads(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (victimOf(item) != null) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** The first head in the inventory (the one the sale menu offers), or {@code null}. */
    public static ItemStack firstHead(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (victimOf(item) != null) {
                return item;
            }
        }
        return null;
    }

    // ---- price ----------------------------------------------------------------------------------------------------

    /** The price of one particular head: what its owner is, and how he died as written on this head. */
    public Quote quote(ItemStack head) {
        UUID victim = victimOf(head);
        if (victim == null) {
            throw new IllegalArgumentException("not a delivered head");
        }
        return quote(victim, detailsOf(head));
    }

    public Quote quote(UUID victim, HeadPriceRules.Details details) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(victim);
        String name = owner.getName() == null ? "?" : owner.getName();
        long price = HeadPriceRules.price(subject(owner, details), params());
        long left = HeadPriceRules.cooldownLeft(lastSold.getOrDefault(victim, 0L), System.currentTimeMillis(), cooldownMillis());
        return new Quote(victim, name, price, left);
    }

    private Subject subject(OfflinePlayer owner, HeadPriceRules.Details details) {
        double hours = 0.0;
        int kills = 0;
        try {
            hours = owner.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20.0 / 3600.0;
            kills = owner.getStatistic(Statistic.PLAYER_KILLS);
        } catch (RuntimeException ignored) {
            // No statistics file yet: the head is simply worth the base price.
        }
        int reputation = -1;
        int playstyle = -1;
        try {
            reputation = LoveCore.service(ReputationOracle.class).map(o -> o.reputation(owner.getUniqueId())).orElse(-1);
            playstyle = LoveCore.service(BehaviorLevels.class).map(l -> l.playstyleLevel(owner.getUniqueId())).orElse(-1);
        } catch (Throwable ignored) {
            // The social services are optional.
        }
        return new Subject(hours, kills, reputation, playstyle, BehaviorLevels.MAX_LEVEL, details);
    }

    private Params params() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("wanderer.head-sale");
        double low = c == null ? 0.7 : c.getDouble("reputation-factor.low", 0.7);
        double high = c == null ? 1.4 : c.getDouble("reputation-factor.high", 1.4);
        return new Params(
                Money.scaled(c, "base", 1000L),
                Money.scaled(c, "per-hour", 10L),
                c == null ? 500.0 : c.getDouble("hours-cap", 500.0),
                Money.scaled(c, "per-kill", 20L),
                c == null ? 200 : c.getInt("kills-cap", 200),
                low,
                Math.max(low, high),
                (c == null ? 40.0 : c.getDouble("notoriety-bonus-percent", 40.0)) / 100.0,
                Money.scaled(c, "min-price", 200L),
                Money.scaled(c, "max-price", 10000L),
                circumstances(c == null ? null : c.getConfigurationSection("circumstances")));
    }

    private HeadPriceRules.Circumstances circumstances(ConfigurationSection c) {
        if (c == null) {
            return HeadPriceRules.Circumstances.NEUTRAL;
        }
        return new HeadPriceRules.Circumstances(
                table(c.getConfigurationSection("cause")), c.getDouble("cause.default", 1.0),
                table(c.getConfigurationSection("weapon")), c.getDouble("weapon.default", 1.0),
                c.getDouble("armor-bonus-percent-per-point", 0.0) / 100.0,
                c.getDouble("armor-bonus-cap-percent", 0.0) / 100.0,
                c.getDouble("reward-share-percent", 0.0) / 100.0,
                Money.scaled(c, "reward-cap", 0L),
                c.getDouble("hunted-bonus-percent-per-day", 0.0) / 100.0,
                c.getDouble("hunted-bonus-cap-percent", 0.0) / 100.0);
    }

    private static java.util.Map<String, Double> table(ConfigurationSection section) {
        var out = new java.util.HashMap<String, Double>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!key.equals("default") && (section.isDouble(key) || section.isInt(key))) {
                    out.put(key.toUpperCase(java.util.Locale.ROOT), section.getDouble(key));
                }
            }
        }
        return out;
    }

    private long cooldownMillis() {
        return Duration.ofDays(Math.max(0, plugin.getConfig().getInt("wanderer.head-sale.victim-cooldown-days", 7))).toMillis();
    }

    // ---- sale -----------------------------------------------------------------------------------------------------

    /** Sells one head of {@code victim} from the player's inventory. Main thread only. */
    public Sale sell(Player player, UUID victim) {
        if (!enabled()) {
            return new Sale(Result.DISABLED, null);
        }
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) {
            return new Sale(Result.NO_ECONOMY, null);
        }
        ItemStack head = null;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && victim.equals(victimOf(item))) {
                head = item;
                break;
            }
        }
        if (head == null) {
            return new Sale(Result.NO_HEAD, quote(victim, null));
        }
        Quote quote = quote(head);
        if (quote.cooldownLeftMillis() > 0L) {
            return new Sale(Result.COOLDOWN, quote);
        }
        head.setAmount(head.getAmount() - 1);
        economy.give(player, quote.price());
        long now = System.currentTimeMillis();
        lastSold.put(victim, now);
        persist(victim, player.getUniqueId(), quote.price(), now);
        return new Sale(Result.SOLD, quote);
    }

    private void persist(UUID victim, UUID seller, long price, long now) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try (Connection connection = plugin.getDatabaseManager().getConnection();
                 PreparedStatement ps = connection.prepareStatement(
                         "INSERT OR REPLACE INTO wanderer_head_sales(victim_uuid, seller_uuid, price, sold_at) VALUES(?,?,?,?)")) {
                ps.setString(1, victim.toString());
                ps.setString(2, seller.toString());
                ps.setLong(3, price);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (Exception e) {
                plugin.getLogger().warning("Странник: не удалось записать продажу головы: " + e.getMessage());
            }
        });
    }
}
