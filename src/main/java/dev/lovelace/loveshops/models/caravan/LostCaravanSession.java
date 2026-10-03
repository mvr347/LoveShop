package dev.lovelace.loveshops.models.caravan;

public record LostCaravanSession(
        int id,
        long scheduledAt,
        long openedAt,
        long closedAt,
        String status,
        String mode,
        int participantCount,
        boolean secretCrate,
        long createdAt
) {}
