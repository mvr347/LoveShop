package dev.lovelace.loveshops.models;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Config-driven roll settings for how "fresh" a Wanderer stock item looks — see
 * {@code wanderer.item-quality} in config.yml. Loaded once per {@code rollRandomItems} call
 * (not per item) and threaded through {@link WandererItemConfig#buildItemStack}.
 *
 * <p>Two independent rolls per generated item:</p>
 * <ul>
 *   <li><b>Durability wear</b> — a damageable item is pristine only {@code pristineChance} of
 *   the time; otherwise it comes in with a random amount of wear between
 *   {@code minWearPercent} and {@code maxWearPercent} of its max durability. Always-pristine
 *   gear read as obviously freshly generated, not like something the Wanderer actually found.</li>
 *   <li><b>Enchantment quality</b> — the Wanderer's stock is deliberately biased ABOVE ordinary
 *   loot: {@code fullEnchantChance} keeps every enchant the pool entry declares,
 *   {@code partialEnchantChance} keeps only the strongest half (min 1), and the remainder drops
 *   enchants entirely — but even that "worst roll" only applies to the subset of items that
 *   were already hand-picked as Wanderer-tier, so the floor is still well above a random mob
 *   drop.</li>
 * </ul>
 */
public record WandererItemQuality(
    boolean durabilityWearEnabled,
    double pristineChance,
    double minWearPercent,
    double maxWearPercent,
    boolean enchantQualityEnabled,
    double fullEnchantChance,
    double partialEnchantChance
) {
    public static WandererItemQuality fromConfig(FileConfiguration config) {
        return new WandererItemQuality(
            config.getBoolean("wanderer.item-quality.durability.enabled", true),
            clamp01(config.getDouble("wanderer.item-quality.durability.pristine-chance", 0.35)),
            Math.max(0, config.getDouble("wanderer.item-quality.durability.min-wear-percent", 5)),
            Math.max(0, config.getDouble("wanderer.item-quality.durability.max-wear-percent", 60)),
            config.getBoolean("wanderer.item-quality.enchant-quality.enabled", true),
            clamp01(config.getDouble("wanderer.item-quality.enchant-quality.full-enchant-chance", 0.65)),
            clamp01(config.getDouble("wanderer.item-quality.enchant-quality.partial-enchant-chance", 0.25))
        );
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** No wear, no enchant thinning — used where quality rolls shouldn't apply (e.g. legacy call sites). */
    public static final WandererItemQuality DISABLED =
        new WandererItemQuality(false, 1.0, 0, 0, false, 1.0, 0.0);
}
