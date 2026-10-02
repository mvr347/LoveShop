package dev.lovelace.loveshops.models.caravan;

import java.util.List;

/**
 * Конфигурация шаблона ящика из пула каравана (crates-pool).
 */
public record DailyCrateConfig(
        String key,
        String displayName,
        String iconId,
        List<DailyCrateAcceptedItem> acceptedItems,
        int maxStacks,
        int stackBonusPercent
) {}
