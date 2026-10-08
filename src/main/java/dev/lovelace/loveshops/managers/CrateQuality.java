package dev.lovelace.loveshops.managers;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Quality variations of a caravan crate: how many loot rolls it gets and how much the roll chances are scaled.
 * Used to be hardcoded in {@code openCrateItem}; now read from {@code caravan.lost.quality.<default|secret>}.
 */
public record CrateQuality(String id, double weight, int rolls, double chanceMultiplier) {

    public static List<CrateQuality> defaults(boolean secret) {
        return secret
                ? List.of(new CrateQuality("bad", 15, 2, 0.7), new CrateQuality("normal", 40, 3, 1.0), new CrateQuality("good", 45, 5, 1.25))
                : List.of(new CrateQuality("bad", 30, 1, 0.55), new CrateQuality("normal", 50, 3, 1.0), new CrateQuality("good", 20, 4, 1.2));
    }

    /** Reads the tiers of one crate type; a missing or broken section falls back to the built-in defaults. */
    public static List<CrateQuality> fromConfig(ConfigurationSection section, boolean secret) {
        List<CrateQuality> defaults = defaults(secret);
        if (section == null) return defaults;
        List<CrateQuality> result = new ArrayList<>();
        for (CrateQuality fallback : defaults) {
            ConfigurationSection tier = section.getConfigurationSection(fallback.id());
            if (tier == null) {
                result.add(fallback);
                continue;
            }
            double weight = Math.max(0.0, tier.getDouble("weight", fallback.weight()));
            int rolls = Math.max(1, tier.getInt("rolls", fallback.rolls()));
            double mul = Math.max(0.0, tier.getDouble("chance-multiplier", fallback.chanceMultiplier()));
            result.add(new CrateQuality(fallback.id(), weight, rolls, mul));
        }
        return result.stream().mapToDouble(CrateQuality::weight).sum() > 0 ? result : defaults;
    }

    /** Picks a tier by weight; {@code roll} is uniform in [0, 1). */
    public static CrateQuality pick(List<CrateQuality> tiers, double roll) {
        double total = tiers.stream().mapToDouble(CrateQuality::weight).sum();
        double target = roll * total;
        double acc = 0;
        for (CrateQuality tier : tiers) {
            acc += tier.weight();
            if (target < acc) return tier;
        }
        return tiers.get(tiers.size() - 1);
    }
}
