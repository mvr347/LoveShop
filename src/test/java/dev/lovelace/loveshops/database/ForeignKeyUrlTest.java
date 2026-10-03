package dev.lovelace.loveshops.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The connection URL must switch foreign keys on for every new connection, or the cascades never run. */
class ForeignKeyUrlTest {

    private static int childrenAfterParentDelete(String url) throws SQLException {
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            st.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, pid INTEGER NOT NULL REFERENCES parent(id) ON DELETE CASCADE)");
            st.execute("INSERT INTO parent (id) VALUES (1)");
            st.execute("INSERT INTO child (id, pid) VALUES (1, 1)");
            st.execute("DELETE FROM parent WHERE id = 1");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM child")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void cascadeRunsOnlyWithTheFlagInTheUrl(@TempDir Path dir) throws SQLException {
        String base = "jdbc:sqlite:" + dir.resolve("a.db");
        assertEquals(1, childrenAfterParentDelete(base + "?journal_mode=WAL&busy_timeout=5000"),
                "without the flag the child survives");
        assertEquals(0, childrenAfterParentDelete("jdbc:sqlite:" + dir.resolve("b.db")
                + "?journal_mode=WAL&busy_timeout=5000&foreign_keys=true"), "with the flag the cascade removes it");
    }
}
