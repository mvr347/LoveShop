package dev.lovelace.loveshops.npc;

import dev.lovelace.loveshops.models.NpcData;
import java.sql.Connection;
import java.sql.SQLException;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Provider names can be plain text; comparing rendered names preserves existing stored colours. */
public final class NpcNames {
    private NpcNames() {}

    public static boolean changed(NpcData row, String name) {
        return name != null && !name.isBlank()
                && !name.equals(row.name())
                && !name.equals(PlainTextComponentSerializer.plainText().serialize(NpcText.section(row.name())));
    }

    public static NpcData write(Connection db, NpcData row, String name) throws SQLException {
        if (!changed(row, name)) return row;
        long now = System.currentTimeMillis() / 1000;
        try (var update = db.prepareStatement(
                "UPDATE shops_npcs SET name=?,updated_at=? WHERE uuid=? AND name=?")) {
            update.setString(1, name);
            update.setLong(2, now);
            update.setString(3, row.uuid().toString());
            update.setString(4, row.name());
            if (update.executeUpdate() != 1) throw new SQLException("NPC name changed concurrently");
        }
        return new NpcData(row.id(), row.uuid(), row.type(), row.world(), row.x(), row.y(), row.z(),
                row.yaw(), row.pitch(), name, row.displayName(), row.skinOwner(), row.npcKey(),
                row.createdAt(), now);
    }
}
