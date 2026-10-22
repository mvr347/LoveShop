package dev.lovelace.loveshops.database;

import dev.lovelace.loveshops.npc.NpcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class NpcKeyMigrationTest {

    private static final Logger LOG = Logger.getLogger("test");
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            // The schema as it was before the key columns: only the INTEGER Citizens ids.
            st.execute("CREATE TABLE shops_npcs (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE NOT NULL, type TEXT, citizens_id INTEGER)");
            st.execute("CREATE TABLE trade_points (claim_id TEXT PRIMARY KEY, npc_citizens_id INTEGER, guard_citizens_id INTEGER)");
            st.execute("INSERT INTO shops_npcs (uuid, type, citizens_id) VALUES ('a', 'banker', 12), ('b', 'wanderer', NULL), ('c', 'banker', 0)");
            st.execute("INSERT INTO trade_points VALUES ('p1', 7, 8), ('p2', 9, NULL), ('p3', NULL, NULL)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private List<String> column(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    void legacyIdsBecomeTextKeysAndOldColumnsStayReadable() throws Exception {
        assertEquals(2 + 2 + 1, NpcKeyMigration.migrate(conn, LOG));
        // id 0 is a real Citizens id and must not be mistaken for "no id"
        assertEquals(java.util.Arrays.asList("12", null, "0"), column("SELECT npc_key FROM shops_npcs ORDER BY id"));
        assertEquals(java.util.Arrays.asList("7", "9", null), column("SELECT npc_key FROM trade_points ORDER BY claim_id"));
        assertEquals(java.util.Arrays.asList("8", null, null), column("SELECT guard_key FROM trade_points ORDER BY claim_id"));
        // the legacy columns are untouched
        assertEquals(java.util.Arrays.asList("12", null, "0"), column("SELECT citizens_id FROM shops_npcs ORDER BY id"));
        assertEquals(java.util.Arrays.asList("7", "9", null), column("SELECT npc_citizens_id FROM trade_points ORDER BY claim_id"));
    }

    @Test
    void secondRunChangesNothing() throws Exception {
        NpcKeyMigration.migrate(conn, LOG);
        assertEquals(0, NpcKeyMigration.migrate(conn, LOG));
        assertEquals(java.util.Arrays.asList("12", null, "0"), column("SELECT npc_key FROM shops_npcs ORDER BY id"));
    }

    @Test
    void existingKeyIsNeverOverwritten() throws Exception {
        NpcKeyMigration.migrate(conn, LOG);
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE shops_npcs SET npc_key = 'ext-1a2b3c4d' WHERE uuid = 'a'");
            // a row bound before the migration ran again, with a stale legacy id next to a newer key
            st.execute("UPDATE trade_points SET npc_key = 'ext-77' WHERE claim_id = 'p1'");
        }
        assertEquals(0, NpcKeyMigration.migrate(conn, LOG));
        assertEquals("ext-1a2b3c4d", column("SELECT npc_key FROM shops_npcs WHERE uuid = 'a'").get(0));
        assertEquals("ext-77", column("SELECT npc_key FROM trade_points WHERE claim_id = 'p1'").get(0));
    }

    @Test
    void rowWrittenByTheOldBuildAfterwardsIsPickedUpOnTheNextStart() throws Exception {
        NpcKeyMigration.migrate(conn, LOG);
        try (Statement st = conn.createStatement()) {
            // a rollback window: the previous build writes only the INTEGER column
            st.execute("INSERT INTO shops_npcs (uuid, type, citizens_id) VALUES ('d', 'banker', 31)");
        }
        assertEquals(1, NpcKeyMigration.migrate(conn, LOG));
        assertEquals("31", column("SELECT npc_key FROM shops_npcs WHERE uuid = 'd'").get(0));
    }

    @Test
    void missingTablesAreSkipped() throws Exception {
        try (Connection empty = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            assertEquals(0, NpcKeyMigration.migrate(empty, LOG));
        }
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE trade_points");
        }
        assertEquals(2, NpcKeyMigration.migrate(conn, LOG));
    }

    @Test
    void keyColumnWithoutLegacyColumnIsStillAdded() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE shops_npcs");
            st.execute("CREATE TABLE shops_npcs (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE NOT NULL)");
        }
        NpcKeyMigration.migrate(conn, LOG);
        try (Statement st = conn.createStatement()) {
            st.execute("INSERT INTO shops_npcs (uuid, npc_key) VALUES ('x', 'ext-9')");
        }
        assertEquals("ext-9", column("SELECT npc_key FROM shops_npcs").get(0));
    }

    @Test
    void readFallsBackToTheLegacyColumnAndWritesBothForNumericKeys() throws Exception {
        // before the migration has run on this row, reading still finds the id
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE shops_npcs ADD COLUMN npc_key TEXT");
        }
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT npc_key, citizens_id FROM shops_npcs ORDER BY id")) {
            rs.next();
            assertEquals("12", NpcKeys.read(rs, "npc_key", "citizens_id"));
            rs.next();
            assertNull(NpcKeys.read(rs, "npc_key", "citizens_id"));
        }
        try (PreparedStatement ps = conn.prepareStatement("UPDATE shops_npcs SET npc_key = ?, citizens_id = ? WHERE uuid = 'b'")) {
            NpcKeys.write(ps, 1, 2, "ext-5");
            ps.executeUpdate();
            NpcKeys.write(ps, 1, 2, "15");
            ps.executeUpdate();
        }
        assertEquals("15", column("SELECT npc_key FROM shops_npcs WHERE uuid = 'b'").get(0));
        assertEquals("15", column("SELECT citizens_id FROM shops_npcs WHERE uuid = 'b'").get(0));
        try (PreparedStatement ps = conn.prepareStatement("UPDATE shops_npcs SET npc_key = ?, citizens_id = ? WHERE uuid = 'b'")) {
            NpcKeys.write(ps, 1, 2, "ext-5");
            ps.executeUpdate();
        }
        assertEquals("ext-5", column("SELECT npc_key FROM shops_npcs WHERE uuid = 'b'").get(0));
        assertNull(column("SELECT citizens_id FROM shops_npcs WHERE uuid = 'b'").get(0));
        try (PreparedStatement ps = conn.prepareStatement("UPDATE shops_npcs SET npc_key = ?, citizens_id = ? WHERE uuid = 'b'")) {
            NpcKeys.write(ps, 1, 2, null);
            ps.executeUpdate();
        }
        assertNull(column("SELECT npc_key FROM shops_npcs WHERE uuid = 'b'").get(0));
    }
}
