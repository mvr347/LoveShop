package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.Denomination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits an amount into coins, biggest denomination first - the same way
 * {@code LoveEconomy#give} pays it out. No Bukkit here, so it is unit-testable.
 */
public final class MoneySplit {

    /** {@code count} coins of {@code denomination}. */
    public record Part(Denomination denomination, long count) {}

    private MoneySplit() {}

    /**
     * @return coins that add up to as much of {@code amount} as the denominations can express;
     *         a remainder smaller than the smallest denomination is dropped (cannot be paid anyway)
     */
    public static List<Part> split(long amount, List<Denomination> denominations) {
        List<Part> parts = new ArrayList<>();
        if (amount <= 0 || denominations == null || denominations.isEmpty()) return parts;
        List<Denomination> sorted = new ArrayList<>(denominations);
        sorted.sort(Comparator.comparingLong(Denomination::value).reversed());
        long remaining = amount;
        for (Denomination den : sorted) {
            long count = remaining / den.value();
            if (count > 0) {
                parts.add(new Part(den, count));
                remaining -= count * den.value();
            }
        }
        return parts;
    }

    /** Smallest denomination, or {@code null} when there are none. */
    public static Denomination smallest(List<Denomination> denominations) {
        if (denominations == null || denominations.isEmpty()) return null;
        return denominations.stream().min(Comparator.comparingLong(Denomination::value)).orElse(null);
    }
}
