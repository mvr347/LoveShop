package dev.lovelace.loveshops.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Adds the text NPC-key columns next to the three INTEGER columns that held Citizens ids, and copies the ids over.
 *
 * <p>Safe to run on every start: a column is only added when missing, and only rows whose key is still empty are
 * filled, so a key written later (a LoveEssential one, or an edit by hand) is never overwritten. The old columns
 * are left in place and keep being written for numeric keys, which keeps a rollback to the previous build working.</p>
 */
public final class NpcKeyMigration {

    private NpcKeyMigration() {
    }

    record Target(String table, String legacyColumn, String keyColumn) {
    }

    static final List<Target> TARGETS = List.of(
            new Target("shops_npcs", "citizens_id", "npc_key"),
            new Target("trade_points", "npc_citizens_id", "npc_key"),
            new Target("trade_points", "guard_citizens_id", "guard_key"));

    /** @return number of rows whose key was filled from the legacy id in this run. */
    public static int migrate(Connection conn, Logger log) throws SQLException {
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        int filled = 0;
        try (Statement st = conn.createStatement()) {
            for (Target t : TARGETS) {
                List<String> columns = columnsOf(conn, t.table());
                if (columns.isEmpty()) continue; // table does not exist (yet)
                if (!columns.contains(t.keyColumn())) {
                    st.execute("ALTER TABLE " + t.table() + " ADD COLUMN " + t.keyColumn() + " TEXT");
                }
                if (!columns.contains(t.legacyColumn())) continue;
                filled += st.executeUpdate("UPDATE " + t.table() + " SET " + t.keyColumn() + " = CAST(" + t.legacyColumn()
                        + " AS TEXT) WHERE (" + t.keyColumn() + " IS NULL OR " + t.keyColumn() + " = '') AND "
                        + t.legacyColumn() + " IS NOT NULL");
            }
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
        if (filled > 0 && log != null) {
            log.info("NPC: ключи Citizens-id перенесены в текстовые колонки: " + filled + " зап.");
        }
        return filled;
    }

    private static List<String> columnsOf(Connection conn, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) columns.add(rs.getString("name"));
        }
        return columns;
    }
}
