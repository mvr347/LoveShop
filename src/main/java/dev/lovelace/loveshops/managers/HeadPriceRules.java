package dev.lovelace.loveshops.managers;

import java.util.Map;

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
                         long minPrice, long maxPrice, Circumstances circumstances) {
        public Params {
            circumstances = circumstances == null ? Circumstances.NEUTRAL : circumstances;
        }

        public Params(long base, long perHour, double hoursCap, long perKill, int killsCap,
                      double reputationLow, double reputationHigh, double notorietyBonus,
                      long minPrice, long maxPrice) {
            this(base, perHour, hoursCap, perKill, killsCap, reputationLow, reputationHigh, notorietyBonus,
                    minPrice, maxPrice, Circumstances.NEUTRAL);
        }
    }

    /**
     * What the way the player died and the hunt itself add to the price. {@code causes} and {@code weapons} are
     * multipliers by name (a weapon is looked up by its full name, then by the part before the first underscore, so
     * {@code NETHERITE} covers every netherite tool). Armor and the length of the hunt add a share per point or per
     * day up to a cap; the bounty reward adds {@code rewardShare} of its value up to {@code rewardCap}.
     */
    public record Circumstances(Map<String, Double> causes, double causeDefault,
                                Map<String, Double> weapons, double weaponDefault,
                                double armorPerPoint, double armorCap,
                                double rewardShare, long rewardCap,
                                double huntedPerDay, double huntedCap) {
        public static final Circumstances NEUTRAL =
                new Circumstances(Map.of(), 1.0, Map.of(), 1.0, 0.0, 0.0, 0.0, 0L, 0.0, 0.0);
    }

    /** How the player died and how long he was hunted, as written on the head by LoveHunt; see {@link #parse}. */
    public record Details(String cause, String weapon, double armor, long reward, double huntedDays) {
        /** Reads {@code cause=PROJECTILE;weapon=BOW;armor=18.0;reward=4000;hunted=86400000}; damaged parts count as zero. */
        public static Details parse(String text) {
            if (text == null || text.isBlank()) {
                return null;
            }
            String cause = "";
            String weapon = "";
            double armor = 0.0;
            long reward = 0L;
            long hunted = 0L;
            for (String part : text.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                try {
                    switch (key) {
                        case "cause" -> cause = value;
                        case "weapon" -> weapon = value;
                        case "armor" -> armor = Double.parseDouble(value);
                        case "reward" -> reward = Long.parseLong(value);
                        case "hunted" -> hunted = Long.parseLong(value);
                        default -> {
                        }
                    }
                } catch (NumberFormatException ignored) {
                    // A damaged number counts as zero.
                }
            }
            return new Details(cause, weapon, Double.isFinite(armor) ? Math.max(0.0, armor) : 0.0, Math.max(0L, reward),
                    Math.max(0L, hunted) / 86_400_000.0);
        }
    }

    /**
     * What is known about the player the head belongs to. {@code reputation} is 0..100, or -1 when unknown;
     * {@code playstyle} is 0 (most aggressive) .. {@code kindest}, or -1 when unknown.
     */
    public record Subject(double playHours, int kills, int reputation, int playstyle, int kindest, Details details) {
        public Subject(double playHours, int kills, int reputation, int playstyle, int kindest) {
            this(playHours, kills, reputation, playstyle, kindest, null);
        }
    }

    public static long price(Subject subject, Params params) {
        double hours = Math.min(Math.max(0.0, subject.playHours()), Math.max(0.0, params.hoursCap()));
        int kills = Math.min(Math.max(0, subject.kills()), Math.max(0, params.killsCap()));
        double raw = params.base() + hours * params.perHour() + (double) kills * params.perKill();
        double price = raw * reputationFactor(subject.reputation(), params) * notorietyFactor(subject, params)
                * circumstanceFactor(subject.details(), params.circumstances()) + rewardPart(subject.details(), params.circumstances());
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

    /** Cause of death and weapon multiply; the armor worn and the length of the hunt add a capped share. */
    static double circumstanceFactor(Details details, Circumstances c) {
        if (details == null) {
            return 1.0;
        }
        double cause = c.causes().getOrDefault(details.cause(), c.causeDefault());
        double weapon = lookup(c.weapons(), details.weapon(), c.weaponDefault());
        double bonus = Math.min(Math.max(0.0, c.armorCap()), details.armor() * Math.max(0.0, c.armorPerPoint()))
                + Math.min(Math.max(0.0, c.huntedCap()), details.huntedDays() * Math.max(0.0, c.huntedPerDay()));
        return Math.max(0.0, cause) * Math.max(0.0, weapon) * (1.0 + bonus);
    }

    /** A share of what the customer paid for the hunt, capped so a huge order cannot be turned into coins. */
    static double rewardPart(Details details, Circumstances c) {
        if (details == null || c.rewardShare() <= 0.0 || c.rewardCap() <= 0L) {
            return 0.0;
        }
        return Math.min((double) c.rewardCap(), details.reward() * c.rewardShare());
    }

    private static double lookup(Map<String, Double> table, String name, double fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        Double exact = table.get(name);
        if (exact != null) {
            return exact;
        }
        int underscore = name.indexOf('_');
        Double prefix = underscore > 0 ? table.get(name.substring(0, underscore)) : null;
        return prefix != null ? prefix : fallback;
    }

    /** Milliseconds left until a head of the same player may be sold again, or 0. */
    public static long cooldownLeft(long lastSoldAt, long now, long cooldownMillis) {
        if (lastSoldAt <= 0L || cooldownMillis <= 0L) {
            return 0L;
        }
        return Math.max(0L, lastSoldAt + cooldownMillis - now);
    }
}
