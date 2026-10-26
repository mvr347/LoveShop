package dev.lovelace.loveshops.npc;

import dev.lovelace.loveshops.models.NpcData;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Replaces a provider link in place, preserving the shop row identity and all business settings.
 */
public final class NpcRebind {
    private NpcRebind() {}

    public static NpcData target(
            NpcData old,
            String key,
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            long now) {
        return new NpcData(
                old.id(),
                old.uuid(),
                old.type(),
                world,
                x,
                y,
                z,
                yaw,
                pitch,
                old.name(),
                old.displayName(),
                old.skinOwner(),
                key,
                old.createdAt(),
                now);
    }

    public static void write(Connection connection, NpcData before, NpcData after)
            throws SQLException {
        if (before.id() != after.id() || !before.uuid().equals(after.uuid()))
            throw new SQLException("Данные NPC изменились.");
        try (var update =
                connection.prepareStatement(
                        """
                        UPDATE shops_npcs SET npc_key=?,citizens_id=?,world=?,x=?,y=?,z=?,yaw=?,pitch=?,updated_at=?
                        WHERE uuid=? AND COALESCE(NULLIF(TRIM(npc_key),''),CAST(citizens_id AS TEXT)) IS ? AND COALESCE(updated_at,0)=?
                        """)) {
            NpcKeys.write(update, 1, 2, after.npcKey());
            update.setString(3, after.world());
            update.setDouble(4, after.x());
            update.setDouble(5, after.y());
            update.setDouble(6, after.z());
            update.setFloat(7, after.yaw());
            update.setFloat(8, after.pitch());
            update.setLong(9, after.updatedAt());
            update.setString(10, before.uuid().toString());
            update.setString(11, before.npcKey());
            update.setLong(12, before.updatedAt());
            if (update.executeUpdate() != 1)
                throw new SQLException("Данные NPC изменились. Откройте список заново.");
        }
    }
}
