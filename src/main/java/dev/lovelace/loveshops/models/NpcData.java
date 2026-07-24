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
    long createdAt,
    long updatedAt
) {}
