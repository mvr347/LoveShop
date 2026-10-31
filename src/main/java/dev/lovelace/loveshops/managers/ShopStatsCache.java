package dev.lovelace.loveshops.managers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Per-player trade-point and trader numbers for PlaceholderAPI. Placeholders are asked on the main
 * thread, where the database must not be touched, so answers come from this cache; an old or missing
 * entry starts a background reload and the previous value is served meanwhile.
 */
public final class ShopStatsCache {

    /** What the placeholders show. {@code ratingStars} is the average of visible ratings, 0 when unrated. */
    public record Values(int points, long salesTotal, long revenueTotal, double ratingStars, int ratings,
                         int traderLevel, long totalSold, long totalBought) {
        public static final Values EMPTY = new Values(0, 0, 0, 0, 0, 1, 0, 0);
    }

    /** Where the numbers come from; the database in the plugin, a fake in tests. */
    public interface Source {
        Values load(UUID player) throws SQLException;
    }

    private record Entry(Values values, long loadedAt) {}

    private final Source source;
    private final Executor io;
    private final LongSupplier clock;
    private final long ttlMillis;
    private final Logger log;
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final Set<UUID> loading = ConcurrentHashMap.newKeySet();

    public ShopStatsCache(Source source, Executor io, LongSupplier clock, long ttlMillis, Logger log) {
        this.source = source;
        this.io = io;
        this.clock = clock;
        this.ttlMillis = ttlMillis;
        this.log = log;
    }

    /** The cached numbers, or empty before the first load finished; refreshes in the background when old. */
    public Optional<Values> get(UUID player) {
        Entry entry = entries.get(player);
        if (entry == null || clock.getAsLong() - entry.loadedAt() >= ttlMillis) refresh(player);
        return Optional.ofNullable(entry).map(Entry::values);
    }

    private void refresh(UUID player) {
        if (!loading.add(player)) return;
        try {
            io.execute(() -> {
                try {
                    entries.put(player, new Entry(source.load(player), clock.getAsLong()));
                } catch (SQLException | RuntimeException e) {
                    log.warning("Статистика торговли игрока не загружена: " + e.getMessage());
                } finally {
                    loading.remove(player);
                }
            });
        } catch (RuntimeException rejected) {
            loading.remove(player);
        }
    }

    /** The real source: three small queries on one pooled connection. */
    public static Source databaseSource(dev.lovelace.loveshops.database.DatabaseManager database) {
        return player -> {
            String id = player.toString();
            try (Connection conn = database.getConnection()) {
                int points;
                long sales;
                long revenue;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*), COALESCE(SUM(sales_total),0), COALESCE(SUM(revenue_total),0) FROM trade_points WHERE owner_uuid = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        points = rs.getInt(1);
                        sales = rs.getLong(2);
                        revenue = rs.getLong(3);
                    }
                }
                double stars = 0;
                int ratings = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COALESCE(AVG(r.stars),0), COUNT(r.id) FROM stall_ratings r "
                                + "JOIN trade_points p ON p.claim_id = r.point_id WHERE p.owner_uuid = ? AND r.hidden = 0")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        stars = rs.getDouble(1);
                        ratings = rs.getInt(2);
                    }
                }
                int level = 1;
                long sold = 0;
                long bought = 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT level, total_sold, total_bought FROM player_trader_progress WHERE player_uuid = ?")) {
                    ps.setString(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            level = Math.max(1, rs.getInt(1));
                            sold = rs.getLong(2);
                            bought = rs.getLong(3);
                        }
                    }
                }
                return new Values(points, sales, revenue, stars, ratings, level, sold, bought);
            }
        };
    }
}
