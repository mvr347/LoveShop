package dev.lovelace.loveshops.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

/**
 * One-time rescale of stored amounts when LoveCore's {@code economy.scale-version} grows.
 *
 * <p>Two factors, because two kinds of numbers live in the database: <b>money</b> held or owed (cash box, pending
 * payouts, deposits - {@code economy.migration.money-factor}) and <b>item prices</b> set by players or by the
 * plugin (stall listings, commission lots - {@code economy.migration.price-factor}), which only follow the
 * price model's rescale of goods and are therefore moved by a smaller factor. The version the data was written under
 * is stored in {@code economy_meta}; the rescale and the version write share one transaction.</p>
 */
public final class EconomyMigration {

    private static final String META_KEY = "scale_version";

    private EconomyMigration() {
    }

    enum Kind { MONEY, PRICE }

    record Target(String table, String column, String where, Kind kind) {
    }

    /**
     * Rows that carry live amounts. In-flight lost-caravan lots and bids are deliberately not listed: a bid has already
     * been charged in coins, so rescaling it would mint money on refund - deploy outside of an auction.
     */
    static final List<Target> TARGETS = List.of(
            new Target("trade_points", "till_coins", null, Kind.MONEY),
            new Target("commission_pending_payouts", "amount", null, Kind.MONEY),
            new Target("lost_caravan_participants", "entry_fee_paid", "refunded = 0 AND won_crates = 0", Kind.MONEY),
            new Target("stall_listings", "unit_price", null, Kind.PRICE),
            new Target("commission_lots", "price", "status = 'ACTIVE'", Kind.PRICE),
            new Target("buyer_inventory", "base_price", "sold_at IS NULL", Kind.PRICE));

    /** True when the stored version is lower than the target (or absent) and some row carries an amount. */
    public static boolean needsRescale(Connection conn, int targetVersion) throws SQLException {
        ensureMeta(conn);
        Integer stored = readVersion(conn);
        if (stored != null && stored >= targetVersion) return false;
        for (Target t : TARGETS) {
            if (!tableExists(conn, t.table())) continue;
            String sql = "SELECT 1 FROM " + t.table() + " WHERE " + t.column() + " > 0"
                    + (t.where() != null ? " AND " + t.where() : "") + " LIMIT 1";
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    /** @return number of rows rescaled. */
    public static int migrate(Connection conn, int targetVersion, double moneyFactor, double priceFactor, Logger log)
            throws SQLException {
        ensureMeta(conn);
        Integer stored = readVersion(conn);
        if (stored != null && stored >= targetVersion) return 0;

        boolean wasAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int from = stored != null ? stored : 1;
            int rows = 0;
            if (from < targetVersion) {
                int steps = targetVersion - from;
                for (Target t : TARGETS) {
                    double factor = t.kind() == Kind.MONEY ? moneyFactor : priceFactor;
                    if (factor > 0 && factor != 1.0) {
                        rows += scale(conn, t, Math.pow(factor, steps));
                    }
                }
            }
            writeVersion(conn, targetVersion);
            conn.commit();
            if (rows > 0) {
                log.info("Economy migration v" + from + " -> v" + targetVersion + ": rescaled " + rows
                        + " rows (money x" + moneyFactor + ", prices x" + priceFactor + " per step)");
            }
            return rows;
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(wasAuto);
        }
    }

    private static int scale(Connection conn, Target t, double mult) throws SQLException {
        if (!tableExists(conn, t.table())) return 0;
        String sql = "UPDATE " + t.table() + " SET " + t.column() + " = MAX(1, CAST(ROUND(" + t.column() + " * ?) AS INTEGER)) "
                + "WHERE " + t.column() + " > 0" + (t.where() != null ? " AND " + t.where() : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, mult);
            return ps.executeUpdate();
        }
    }

    private static void ensureMeta(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS economy_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Integer readVersion(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM economy_meta WHERE key = ?")) {
            ps.setString(1, META_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                try {
                    return Integer.parseInt(rs.getString(1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
    }

    private static void writeVersion(Connection conn, int version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO economy_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, META_KEY);
            ps.setString(2, String.valueOf(version));
            ps.executeUpdate();
        }
    }
}
