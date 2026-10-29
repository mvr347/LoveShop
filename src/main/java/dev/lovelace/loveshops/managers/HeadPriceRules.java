package dev.lovelace.loveshops.managers;

/** Pure rules for what the Wanderer pays for a player's head (no Bukkit types), so they can be unit-tested. */
public final class HeadPriceRules {
    private HeadPriceRules() {
    }

    /**
     * Prices and caps from the config, all money in the smallest unit. {@code reputationLow}/{@code reputationHigh}
     * are the multipliers at reputation 0 and 100; {@code notorietyBonus} is the extra share (0.4 = +40%) paid for the
     * most aggressive playstyle.
     */
    public record Params(long base, long perHour, double hoursCap, long perKill, int killsCap,
                         double reputationLow, double reputationHigh, double notorietyBonus,
                         long minPrice, long maxPrice) {
    }

    /**
     * What is known about the player the head belongs to. {@code reputation} is 0..100, or -1 when unknown;
     * {@code playstyle} is 0 (most aggressive) .. {@code kindest}, or -1 when unknown.
     */
    public record Subject(double playHours, int kills, int reputation, int playstyle, int kindest) {
    }

    public static long price(Subject subject, Params params) {
        double hours = Math.min(Math.max(0.0, subject.playHours()), Math.max(0.0, params.hoursCap()));
        int kills = Math.min(Math.max(0, subject.kills()), Math.max(0, params.killsCap()));
        double raw = params.base() + hours * params.perHour() + (double) kills * params.perKill();
        double price = raw * reputationFactor(subject.reputation(), params) * notorietyFactor(subject, params);
        long rounded = Math.round(price);
        long floor = Math.max(0L, params.minPrice());
        long ceiling = params.maxPrice() > 0L ? Math.max(floor, params.maxPrice()) : Long.MAX_VALUE;
        return Math.max(floor, Math.min(ceiling, rounded));
    }

    /** A well-regarded player is a prouder trophy; unknown reputation counts as the middle. */
    static double reputationFactor(int reputation, Params params) {
        double share = reputation < 0 ? 0.5 : Math.min(100, reputation) / 100.0;
        return params.reputationLow() + (params.reputationHigh() - params.reputationLow()) * share;
    }

    /** Only the aggressive half of the scale is notorious: full bonus at 0, a third of it at the last aggressive step. */
    static double notorietyFactor(Subject subject, Params params) {
        if (subject.playstyle() < 0 || subject.kindest() <= 0 || params.notorietyBonus() <= 0.0) {
            return 1.0;
        }
        int steps = Math.max(1, subject.kindest() / 2);
        if (subject.playstyle() >= steps) {
            return 1.0;
        }
        return 1.0 + params.notorietyBonus() * (steps - subject.playstyle()) / steps;
    }

    /** Milliseconds left until a head of the same player may be sold again, or 0. */
    public static long cooldownLeft(long lastSoldAt, long now, long cooldownMillis) {
        if (lastSoldAt <= 0L || cooldownMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, lastSoldAt + cooldownMillis - now);
    }
}
