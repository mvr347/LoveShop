package dev.lovelace.loveshops.market;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;

/**
 * Moving items into a player's inventory without ever losing or duplicating them: the caller asks
 * {@link #capacity} first, updates its own records for exactly that many, and only then
 * {@link #give}s them.
 */
public final class ItemTransfer {

    private ItemTransfer() {}

    /** How many items like {@code template} fit into the player's main storage (hotbar + 27 slots). */
    public static int capacity(Player player, ItemStack template) {
        PlayerInventory inv = player.getInventory();
        int max = Math.max(1, template.getMaxStackSize());
        long room = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack existing = inv.getItem(slot);
            if (existing == null || existing.getType().isAir()) {
                room += max;
            } else if (existing.isSimilar(template)) {
                room += Math.max(0, max - existing.getAmount());
            }
            if (room >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) room;
    }

    /**
     * Gives {@code count} items to the player, splitting into stacks.
     *
     * @return how many did NOT fit (0 when everything was given); the caller must not drop these
     *         silently - they belong to the player's returns
     */
    public static int give(Player player, ItemStack template, int count) {
        int left = count;
        int max = Math.max(1, template.getMaxStackSize());
        while (left > 0) {
            int chunk = Math.min(left, max);
            ItemStack stack = template.clone();
            stack.setAmount(chunk);
            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
            int notGiven = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
            left -= chunk;
            if (notGiven > 0) return left + notGiven;
        }
        return 0;
    }
}
