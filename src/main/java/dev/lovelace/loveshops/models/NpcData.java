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
     * Citizens NPC id this row is bound to, or {@code null} for a legacy/no-Citizens row that
     * LoveShops still owns and spawns itself (plain Villager fallback). Bound rows never have
     * their underlying Citizens NPC created or destroyed by LoveShops - only tagged/untagged.
     */
    Integer citizensId,
    long createdAt,
    long updatedAt
) {}
