package dev.lovelace.loveshops.models;

import java.util.UUID;

public record NpcData(
    int id,
    UUID uuid,
    String type,
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    String name,
    String displayName,
    String skinOwner,
    /**
     * Key of the NPC this row is bound to in the configured NPC provider (for Citizens: its numeric id written
     * as text), or {@code null} for an event spawn point / a legacy row without an NPC, for which LoveShops
     * creates the NPC itself.
     */
    String npcKey,
    long createdAt,
    long updatedAt
) {}
