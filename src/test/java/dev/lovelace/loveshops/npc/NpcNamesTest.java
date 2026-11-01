package dev.lovelace.loveshops.npc;

import static org.junit.jupiter.api.Assertions.*;
import dev.lovelace.loveshops.models.NpcData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcNamesTest {
    private NpcData row() {
        return new NpcData(4, UUID.randomUUID(), "caravaner", "world", 1, 64, 2,
                0, 0, "§6Караванщик", "Витрина", "Steve", null, 100, 200);
    }

    @Test
    void plainProviderNameDoesNotEraseStoredFormatting() {
        var row = row();
        assertFalse(NpcNames.changed(row, "Караванщик"));
        assertFalse(NpcNames.changed(row, "§6Караванщик"));
        assertFalse(NpcNames.changed(row, " "));
        assertFalse(NpcNames.changed(row, null));
        assertTrue(NpcNames.changed(row, "Весемир"));
    }

    @Test
    void renamedEventNpcKeepsItsNameAfterReloadAndRejectsStaleWrites() throws Exception {
        var row = row();
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            db.createStatement().execute("CREATE TABLE shops_npcs(uuid TEXT PRIMARY KEY,name TEXT,updated_at INTEGER)");
            try (var insert = db.prepareStatement("INSERT INTO shops_npcs VALUES(?,?,200)")) {
                insert.setString(1, row.uuid().toString());
                insert.setString(2, row.name());
                insert.executeUpdate();
            }
            var saved = NpcNames.write(db, row, "Весемир");
            try (var query = db.createStatement(); var result = query.executeQuery("SELECT name FROM shops_npcs")) {
                assertTrue(result.next());
                assertEquals("Весемир", result.getString(1));
                assertEquals(saved.name(), result.getString(1));
            }
            assertEquals(row.uuid(), saved.uuid());
            assertEquals(row.type(), saved.type());
            assertEquals(row.skinOwner(), saved.skinOwner());
            assertEquals(row.displayName(), saved.displayName());
            assertEquals(row.createdAt(), saved.createdAt());
            assertEquals(row.x(), saved.x());
            assertThrows(SQLException.class, () -> NpcNames.write(db, row, "Устаревшее имя"));
        }
    }
}
