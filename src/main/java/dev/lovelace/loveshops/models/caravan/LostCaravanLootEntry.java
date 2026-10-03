package dev.lovelace.loveshops.models.caravan;

public record LostCaravanLootEntry(
        String itemId,
        double chance,
        int min,
        int max
) {}
