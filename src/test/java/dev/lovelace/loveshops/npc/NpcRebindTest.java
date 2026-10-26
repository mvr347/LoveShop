package dev.lovelace.loveshops.npc;

import static org.junit.jupiter.api.Assertions.*;

import dev.lovelace.loveshops.models.NpcData;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

class NpcRebindTest {
    @Test
    void aLegacyCitizensColumnCanBeReboundWithoutRecreatingTheRow() throws Exception {
        var old =
                new NpcData(
                        4,
                        UUID.randomUUID(),
                        "banker",
                        "world",
                        1,
                        64,
                        2,
                        0,
                        0,
                        "Bank",
                        "Display",
                        "Steve",
                        "8",
                        100,
                        0);
        var next = NpcRebind.target(old, "ext-existing", "world", 1, 64, 2, 0, 0, 201);
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var schema = db.createStatement()) {
                schema.execute(
                        "CREATE TABLE shops_npcs(uuid TEXT PRIMARY KEY,npc_key TEXT,citizens_id"
                            + " INTEGER,world TEXT,x REAL,y REAL,z REAL,yaw REAL,pitch"
                            + " REAL,updated_at INTEGER)");
            }
            try (var insert =
                    db.prepareStatement(
                            "INSERT INTO shops_npcs VALUES(?,NULL,8,'world',1,64,2,0,0,NULL)")) {
                insert.setString(1, old.uuid().toString());
                insert.executeUpdate();
            }
            NpcRebind.write(db, old, next);
            try (var query = db.createStatement();
                    var rows = query.executeQuery("SELECT npc_key,citizens_id FROM shops_npcs")) {
                assertTrue(rows.next());
                assertEquals("ext-existing", rows.getString(1));
                assertNull(rows.getObject(2));
            }
        }
    }

    @Test
    void rebindPreservesRoleAndRelatedBankDataAndRejectsAStaleWrite() throws Exception {
        var old =
                new NpcData(
                        7,
                        UUID.randomUUID(),
                        "banker",
                        "world",
                        1,
                        64,
                        2,
                        0,
                        0,
                        "Bank",
                        "Display",
                        "Steve",
                        "ext-missing",
                        100,
                        200);
        var next = NpcRebind.target(old, "npc-stable", "spawn", 10, 70, 20, 90, 0, 201);
        assertEquals(old.id(), next.id());
        assertEquals(old.uuid(), next.uuid());
        assertEquals(old.type(), next.type());
        assertEquals(old.name(), next.name());
        assertEquals(old.displayName(), next.displayName());
        assertEquals(old.skinOwner(), next.skinOwner());
        assertEquals(old.createdAt(), next.createdAt());
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (var schema = db.createStatement()) {
                schema.execute(
                        "CREATE TABLE shops_npcs(id INTEGER,uuid TEXT PRIMARY KEY,type TEXT,npc_key"
                            + " TEXT,citizens_id INTEGER,world TEXT,x REAL,y REAL,z REAL,yaw"
                            + " REAL,pitch REAL,updated_at INTEGER)");
                schema.execute("CREATE TABLE bank_data(npc_id INTEGER,balance INTEGER)");
                schema.execute("INSERT INTO bank_data VALUES(7,12345)");
            }
            try (var insert =
                    db.prepareStatement(
                            "INSERT INTO shops_npcs"
                                + " VALUES(7,?,'banker','ext-missing',NULL,'world',1,64,2,0,0,200)")) {
                insert.setString(1, old.uuid().toString());
                insert.executeUpdate();
            }
            NpcRebind.write(db, old, next);
            assertThrows(SQLException.class, () -> NpcRebind.write(db, old, next));
            try (var query = db.createStatement();
                    var rows =
                            query.executeQuery(
                                    "SELECT n.id,n.type,n.npc_key,n.world,b.balance FROM shops_npcs"
                                        + " n JOIN bank_data b ON n.id=b.npc_id")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
                assertEquals("banker", rows.getString(2));
                assertEquals("npc-stable", rows.getString(3));
                assertEquals("spawn", rows.getString(4));
                assertEquals(12345, rows.getInt(5));
                assertFalse(rows.next());
            }
        }
    }
}
