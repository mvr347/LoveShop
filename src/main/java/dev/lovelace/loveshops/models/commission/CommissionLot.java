package dev.lovelace.loveshops.models.commission;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Лот, выставленный на продажу у Комиссионера.
 */
public record CommissionLot(
        int id,
        UUID sellerUuid,
        ItemStack item,
        int price,
        int feePercent,
        String status,
        long createdAt,
        boolean hot
) {
    public int feeAmount() {
        return Math.max(0, (int) Math.round(price * (feePercent / 100.0)));
    }

    public int sellerReceives() {
        return Math.max(0, price - feeAmount());
    }
}
