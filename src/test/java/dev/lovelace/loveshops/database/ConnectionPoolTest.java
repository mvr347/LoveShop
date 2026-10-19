package dev.lovelace.loveshops.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionPoolTest {

    @TempDir
    Path dir;

    @Test
    void closeReturnsConnectionAndReusesIt() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        String url = "jdbc:sqlite:" + dir.resolve("t.db");
        ConnectionPool pool = new ConnectionPool(() -> {
            opened.incrementAndGet();
            return DriverManager.getConnection(url);
        }, 2);

        try (Connection c = pool.borrow(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t(x INTEGER)");
        }
        assertEquals(1, pool.idleCount());
        try (Connection c = pool.borrow()) {
            assertFalse(c.isClosed());
        }
        assertEquals(1, opened.get());
        pool.close();
    }

    @Test
    void uncommittedTransactionIsRolledBackOnReturn() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("r.db");
        ConnectionPool pool = new ConnectionPool(() -> DriverManager.getConnection(url), 1);
        try (Connection c = pool.borrow(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t(x INTEGER)");
        }
        try (Connection c = pool.borrow()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO t VALUES (1)");
            }
        }
        try (Connection c = pool.borrow(); Statement st = c.createStatement()) {
            assertTrue(c.getAutoCommit());
            assertFalse(st.executeQuery("SELECT 1 FROM t").next());
        }
        pool.close();
    }

    @Test
    void overflowConnectionsAreClosedForReal() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("o.db");
        ConnectionPool pool = new ConnectionPool(() -> DriverManager.getConnection(url), 1);
        Connection a = pool.borrow();
        Connection b = pool.borrow();
        a.close();
        b.close();
        assertEquals(1, pool.idleCount());
        assertThrows(java.sql.SQLException.class, () -> { pool.close(); pool.borrow(); });
    }

    @Test
    void leakedResultSetDoesNotBreakNextWriterOnPooledConnection() throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("snap.db");
        ConnectionPool pool = new ConnectionPool(() -> {
            Connection c = DriverManager.getConnection(url);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
            }
            return c;
        }, 2);
        try (Connection c = pool.borrow(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE t(x INTEGER)");
            st.execute("INSERT INTO t VALUES (1)");
        }
        // Connection A reads and leaks its statement and result set, then goes back to the pool.
        Connection a = pool.borrow();
        java.sql.ResultSet leaked = a.createStatement().executeQuery("SELECT x FROM t");
        assertTrue(leaked.next());
        a.close();
        // Another connection writes in the meantime.
        Connection b = pool.borrow();
        try (Statement st = b.createStatement()) {
            st.execute("INSERT INTO t VALUES (2)");
        }
        // A is reused from the pool: without closing leaked statements this fails with SQLITE_BUSY_SNAPSHOT.
        try (Connection again = pool.borrow(); Statement st = again.createStatement()) {
            st.execute("INSERT INTO t VALUES (3)");
        }
        b.close();
        pool.close();
    }
}
