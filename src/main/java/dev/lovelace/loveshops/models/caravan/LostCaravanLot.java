package dev.lovelace.loveshops.models.caravan;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record LostCaravanLot(
        int id,
        int sessionId,
        int lotIndex,
        boolean secret,
        ItemStack crateItem,
        int startingPrice,
        int currentBid,
        @Nullable UUID highestBidder,
        String status,
        long startedAt,
        long endedAt
) {}
