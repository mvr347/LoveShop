package dev.lovelace.loveshops.models.caravan;

/**
 * Товар, принимаемый в ящик Караванщика, и его базовая цена за единицу.
 */
public record DailyCrateAcceptedItem(
        String itemId,
        int pricePerUnit
) {}
