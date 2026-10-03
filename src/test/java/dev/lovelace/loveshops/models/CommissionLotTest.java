package dev.lovelace.loveshops.models;

import dev.lovelace.loveshops.models.commission.CommissionLot;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommissionLotTest {

    @Test
    void feeCalculation() {
        CommissionLot lot = new CommissionLot(
                1,
                UUID.randomUUID(),
                null,
                100,
                10,
                "ACTIVE",
                System.currentTimeMillis() / 1000,
                false
        );

        assertEquals(10, lot.feeAmount());
        assertEquals(90, lot.sellerReceives());
    }

    @Test
    void feeCalculationRounding() {
        CommissionLot lot = new CommissionLot(
                2,
                UUID.randomUUID(),
                null,
                15,
                10,
                "ACTIVE",
                System.currentTimeMillis() / 1000,
                true
        );

        // 15 * 0.10 = 1.5 -> round to 2
        assertEquals(2, lot.feeAmount());
        assertEquals(13, lot.sellerReceives());
    }
}
