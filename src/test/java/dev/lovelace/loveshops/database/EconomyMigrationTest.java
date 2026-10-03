package dev.lovelace.loveshops.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EconomyMigrationTest {

    private static final Logger LOG = Logger.getLogger("test");
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE trade_points (claim_id TEXT PRIMARY KEY, till_coins INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE commission_pending_payouts (id INTEGER PRIMARY KEY AUTOINCREMENT, amount INTEGER NOT NULL)");
            st.execute("CREATE TABLE lost_caravan_participants (session_id INTEGER, player_uuid TEXT, entry_fee_paid INTEGER, refunded INTEGER, won_crates INTEGER)");
            st.execute("CREATE TABLE stall_listings (id INTEGER PRIMARY KEY AUTOINCREMENT, unit_price INTEGER NOT NULL)");
            st.execute("CREATE TABLE commission_lots (id INTEGER PRIMARY KEY AUTOINCREMENT, price INTEGER NOT NULL, status TEXT)");
            st.execute("CREATE TABLE buyer_inventory (id INTEGER PRIMARY KEY AUTOINCREMENT, base_price INTEGER NOT NULL, sold_at INTEGER)");
            st.execute("INSERT INTO trade_points VALUES ('a', 1000), ('b', 0)");
            st.execute("INSERT INTO commission_pending_payouts (amount) VALUES (200)");
            st.execute("INSERT INTO lost_caravan_participants VALUES (1, 'x', 1, 0, 0), (1, 'y', 1, 1, 0), (1, 'z', 1, 0, 2)");
            st.execute("INSERT INTO stall_listings (unit_price) VALUES (50), (3)");
            st.execute("INSERT INTO commission_lots (price, status) VALUES (500, 'ACTIVE'), (500, 'SOLD')");
            st.execute("INSERT INTO buyer_inventory (base_price, sold_at) VALUES (10, NULL), (10, 123)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private long scalar(String sql) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void moneyAndPricesUseTheirOwnFactors() throws Exception {
        assertTrue(EconomyMigration.needsRescale(conn, 2));
        int rows = EconomyMigration.migrate(conn, 2, 5.0, 2.0, LOG);
        assertEquals(1 + 1 + 1 + 2 + 1 + 1, rows);
        // money x5
        assertEquals(5000, scalar("SELECT till_coins FROM trade_points WHERE claim_id='a'"));
        assertEquals(0, scalar("SELECT till_coins FROM trade_points WHERE claim_id='b'"));
        assertEquals(1000, scalar("SELECT amount FROM commission_pending_payouts"));
        assertEquals(5, scalar("SELECT entry_fee_paid FROM lost_caravan_participants WHERE player_uuid='x'"));
        // already refunded / winners keep the old number: it is history
        assertEquals(1, scalar("SELECT entry_fee_paid FROM lost_caravan_participants WHERE player_uuid='y'"));
        assertEquals(1, scalar("SELECT entry_fee_paid FROM lost_caravan_participants WHERE player_uuid='z'"));
        // item prices x2, only live rows
        assertEquals(100, scalar("SELECT unit_price FROM stall_listings WHERE id=1"));
        assertEquals(6, scalar("SELECT unit_price FROM stall_listings WHERE id=2"));
        assertEquals(1000, scalar("SELECT price FROM commission_lots WHERE status='ACTIVE'"));
        assertEquals(500, scalar("SELECT price FROM commission_lots WHERE status='SOLD'"));
        assertEquals(20, scalar("SELECT base_price FROM buyer_inventory WHERE sold_at IS NULL"));
        assertEquals(10, scalar("SELECT base_price FROM buyer_inventory WHERE sold_at IS NOT NULL"));
    }

    @Test
    void runsOnlyOnce() throws Exception {
        EconomyMigration.migrate(conn, 2, 5.0, 2.0, LOG);
        assertFalse(EconomyMigration.needsRescale(conn, 2));
        assertEquals(0, EconomyMigration.migrate(conn, 2, 5.0, 2.0, LOG));
        assertEquals(5000, scalar("SELECT till_coins FROM trade_points WHERE claim_id='a'"));
    }

    @Test
    void emptyDatabaseOnlyRecordsTheVersion() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DELETE FROM trade_points");
            st.execute("DELETE FROM commission_pending_payouts");
            st.execute("DELETE FROM lost_caravan_participants");
            st.execute("DELETE FROM stall_listings");
            st.execute("DELETE FROM commission_lots");
            st.execute("DELETE FROM buyer_inventory");
        }
        assertFalse(EconomyMigration.needsRescale(conn, 2));
        assertEquals(0, EconomyMigration.migrate(conn, 2, 5.0, 2.0, LOG));
    }

    @Test
    void missingTablesAreSkipped() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE buyer_inventory");
            st.execute("DROP TABLE commission_lots");
        }
        assertEquals(1 + 1 + 1 + 2, EconomyMigration.migrate(conn, 2, 5.0, 2.0, LOG));
    }

    @Test
    void factorOfOneChangesNothing() throws Exception {
        assertEquals(0, EconomyMigration.migrate(conn, 2, 1.0, 1.0, LOG));
        assertEquals(1000, scalar("SELECT till_coins FROM trade_points WHERE claim_id='a'"));
    }
}
