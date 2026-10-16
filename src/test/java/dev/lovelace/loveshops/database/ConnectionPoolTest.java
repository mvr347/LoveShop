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
}
