package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/**
 * Typed access to the {@code market:} section of config.yml. Values are read on every call so
 * {@code /loveshopsadmin reload} takes effect without re-creating anything.
 */
public final class MarketConfig {

    private final LoveShops plugin;

    public MarketConfig(LoveShops plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection root() {
        return plugin.getConfig().getConfigurationSection("market");
    }

    private int getInt(String path, int def) {
        ConfigurationSection s = root();
        return s == null ? def : s.getInt(path, def);
    }

    private long getLong(String path, long def) {
        ConfigurationSection s = root();
        return s == null ? def : s.getLong(path, def);
    }

    private boolean getBool(String path, boolean def) {
        ConfigurationSection s = root();
        return s == null ? def : s.getBoolean(path, def);
    }

    private String getString(String path, String def) {
        ConfigurationSection s = root();
        return s == null ? def : s.getString(path, def);
    }

    private double getDouble(String path, double def) {
        ConfigurationSection s = root();
        return s == null ? def : s.getDouble(path, def);
    }

    public boolean enabled() { return getBool("enabled", true); }

    public int baseSellSlots() { return Math.max(1, getInt("stalls.base-sell-slots", 5)); }
    public int baseBuySlots() { return Math.max(1, getInt("stalls.base-buy-slots", 5)); }
    public int maxLevel() { return Math.max(1, getInt("stalls.max-level", 10)); }
    public long confirmThreshold() { return Math.max(0L, getLong("stalls.confirm-threshold", 5000L)); }
    public int pendingTimeoutSeconds() { return Math.max(5, getInt("stalls.pending-timeout-seconds", 30)); }

    /** Name shown above the stall NPC; {@code {owner}} is the tenant's name. Legacy {@code &} codes. */
    public String npcNameFormat() { return getString("npc.name-format", "&6Торговец &f{owner}"); }
    public boolean npcLookClose() { return getBool("npc.look-close", true); }
    public String guardName() { return getString("npc.guard-name", "&9Стража"); }
    public String guardSkin() { return getString("npc.guard-skin", ""); }

    /** Largest amount a single buy order may ask for. */
    public int maxBuyAmount() { return Math.max(1, getInt("stalls.max-buy-amount", 4096)); }

    public int promptTimeoutSeconds() { return Math.max(10, getInt("prompt.timeout-seconds", 60)); }

    /** Upper bound for any single price, so a typo cannot overflow sums. */
    public long priceMax() { return Math.max(1L, getLong("price.max", 100_000_000L)); }

    public boolean antiDumpEnabled() { return getBool("anti-dump.enabled", true); }

    /** Lowest allowed unit price for an item (material name upper-case), 0 = no floor. */
    public long minPrice(String itemKey) {
        if (!antiDumpEnabled() || itemKey == null) return 0L;
        ConfigurationSection s = root();
        if (s == null) return 0L;
        return Math.max(0L, s.getLong("anti-dump.min-prices." + itemKey.toUpperCase(Locale.ROOT), 0L));
    }

    /** Optional override of an icon glyph ({@code market.glyphs.<name>}); empty = use the built-in one. */
    public String glyphOverride(String name) { return getString("glyphs." + name.toLowerCase(Locale.ROOT), ""); }

    public double taxRateOverride() { return getDouble("tax.fixed-rate", -1.0); }

    // ----- reputation gates -----
    public boolean gatesEnabled() { return getBool("gates.enabled", true); }
    public ReputationRules.Thresholds thresholds() {
        return new ReputationRules.Thresholds(
                getInt("gates.outcast-max-politeness", 1),
                getInt("gates.aggressive-max-playstyle", 2),
                getInt("gates.perfect-min-politeness", 6),
                getInt("gates.good-min-playstyle", 5));
    }
    /** Chance that an aggressor is simply turned away at a stall. */
    public double aggressorRefuseChance() { return Math.min(1.0, Math.max(0.0, getDouble("gates.aggressor-refuse-chance", 0.75))); }

    // ----- tax -----
    public boolean taxEnabled() { return getBool("tax.enabled", true); }
    public boolean taxExemptPerfect() { return getBool("tax.exempt-perfect", true); }

    // ----- upgrades -----
    public long upgradeCostBase() { return Math.max(0L, getLong("stalls.upgrade-cost-base", 5000L)); }
    public double upgradeCostMultiplier() { return Math.max(1.0, getDouble("stalls.upgrade-cost-multiplier", 1.5)); }

    // ----- rating -----
    public long ratingMinTrade() { return Math.max(0L, getLong("rating.min-trade-amount", 500L)); }
    public int ratingCooldownHours() { return Math.max(0, getInt("rating.cooldown-hours", 48)); }
    public int ratingNewAccountDays() { return Math.max(0, getInt("rating.new-account-days", 7)); }
    public double ratingNewAccountWeight() { return Math.min(1.0, Math.max(0.0, getDouble("rating.new-account-weight", 0.3))); }
    public int ratingMaxComment() { return Math.max(0, getInt("rating.max-comment-length", 100)); }
}
