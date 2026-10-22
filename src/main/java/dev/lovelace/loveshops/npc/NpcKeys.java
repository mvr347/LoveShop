package dev.lovelace.loveshops.npc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * NPC keys as text. A key is whatever the NPC provider hands out: for Citizens the numeric id written as text,
 * for LoveEssential its own id. Older database rows kept a Citizens id in an INTEGER column; those columns stay
 * readable (and are still written for numeric keys), so a rollback to a build that predates the key columns
 * finds its ids where it expects them.
 */
public final class NpcKeys {

    private NpcKeys() {
    }

    /** A legacy numeric Citizens id as a key; {@code null} stays {@code null}. */
    public static String ofLegacyId(Integer legacyId) {
        return legacyId == null ? null : Integer.toString(legacyId);
    }

    /** The numeric id behind a key, or {@code null} when the key is not a plain non-negative integer. */
    public static Integer legacyIdOf(String key) {
        if (key == null) return null;
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > 9) return null;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return Integer.parseInt(trimmed);
    }

    /** The key to use when a row has both a key column and a legacy id column: the key wins. */
    public static String resolve(String key, Integer legacyId) {
        if (key != null && !key.isBlank()) return key.trim();
        return ofLegacyId(legacyId);
    }

    /**
     * Reads a key from a row: the text column first, the legacy INTEGER column when the text one is empty
     * (a row written before the key columns existed and not yet backfilled).
     */
    public static String read(ResultSet rs, String keyColumn, String legacyColumn) throws SQLException {
        String key = rs.getString(keyColumn);
        if (key != null && !key.isBlank()) return key.trim();
        int legacy = rs.getInt(legacyColumn);
        return rs.wasNull() ? null : Integer.toString(legacy);
    }

    /** Writes a key into its text column and, when it is numeric, into the legacy INTEGER column too. */
    public static void write(PreparedStatement ps, int keyIndex, int legacyIndex, String key) throws SQLException {
        if (key == null || key.isBlank()) {
            ps.setNull(keyIndex, Types.VARCHAR);
            ps.setNull(legacyIndex, Types.INTEGER);
            return;
        }
        ps.setString(keyIndex, key.trim());
        Integer legacy = legacyIdOf(key);
        if (legacy == null) ps.setNull(legacyIndex, Types.INTEGER);
        else ps.setInt(legacyIndex, legacy);
    }
}
