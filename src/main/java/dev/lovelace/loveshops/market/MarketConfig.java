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

    // ----- rent reminders -----
    public boolean rentRemindersEnabled() { return getBool("rent.reminders-enabled", true); }

    /** Hours before the end of the rent at which the tenant is reminded, biggest first. */
    public int[] rentReminderHours() {
        ConfigurationSection s = root();
        return RentReminderMath.normalize(s == null ? null : s.getIntegerList("rent.reminder-hours"));
    }

    /** How often (minutes) an overdue tenant is told the point is closed and will be confiscated. */
    public long rentGraceReminderMinutes() { return Math.max(5L, getLong("rent.grace-reminder-minutes", 180L)); }

    public int baseSellSlots() { return Math.max(1, getInt("stalls.base-sell-slots", 5)); }
    public int baseBuySlots() { return Math.max(1, getInt("stalls.base-buy-slots", 5)); }
    public int maxLevel() { return Math.max(1, getInt("stalls.max-level", 10)); }
    public long confirmThreshold() { return Math.max(0L, getLong("stalls.confirm-threshold", 5000L)); }
    public int pendingTimeoutSeconds() { return Math.max(5, getInt("stalls.pending-timeout-seconds", 30)); }

    /** Name shown above the stall NPC; {@code {owner}} is the tenant's name. Legacy {@code &} codes. */
    public String npcNameFormat() { return getString("npc.name-format", "&6Торговая точка\n&f{owner}"); }
    public String statusOpen() { return getString("npc.status-open", "&aОткрыто"); }
    public String statusClosed() { return getString("npc.status-closed", getString("npc.status.closed", "&cЗакрыто")); }
    public String statusRobbed() { return getString("npc.status-robbed", getString("npc.status.robbed", "&4[ОГРАБЛЕНО]")); }
    public String statusSellOnly() { return getString("npc.status-sell-only", "&eВитрина"); }
    public String statusBuyOnly() { return getString("npc.status-buy-only", "&bСкупка"); }
    public String statusActive() { return statusOpen(); }
    public boolean particlesEnabled() { return getBool("particles.enabled", false); }
    public boolean npcParticles() { return getBool("npc.particles", false); }
    public boolean npcAmbientSounds() { return getBool("npc.ambient-sounds", false); }
    public boolean npcLookClose() { return getBool("npc.look-close", true); }
    public String guardName() { return getString("npc.guard-name", "&9Стража"); }
    public String guardSkin() { return getString("npc.guard-skin", ""); }
    public int baseStorageStacks() { return Math.max(1, getInt("stalls.base-storage-stacks", 20)); }
    public int storagePerLevel() { return Math.max(0, getInt("stalls.storage-per-level", 5)); }
    public boolean allowSellOnly() { return getBool("stalls.allow-sell-only", true); }
    public boolean allowBuyOnly() { return getBool("stalls.allow-buy-only", true); }
    public long guardCostPerDay() { return Math.max(0L, getLong("guard.cost-per-day", 1L)); }
    public java.util.List<Integer> guardDurationsDays() {
        ConfigurationSection s = root();
        if (s != null && s.isList("guard.durations-days")) {
            return s.getIntegerList("guard.durations-days");
        }
        return java.util.List.of(1, 3, 7, 14);
    }
    public java.util.List<Integer> guardDurations() { return guardDurationsDays(); }
    public int blacklistMaxEntries() { return Math.max(1, getInt("blacklist.max-entries", 32)); }
    public int discountMaxPercent() { return Math.min(100, Math.max(1, getInt("discount.max-percent", 50))); }
    public boolean closedSignRemoveOnOpen() { return getBool("closed-sign.remove-on-open", true); }
    public java.util.List<String> closedSignLines() {
        ConfigurationSection s = root();
        if (s != null && s.isList("closed-sign.lines")) {
            return s.getStringList("closed-sign.lines");
        }
        return java.util.List.of("&cТорговая точка", "&cзакрыта", "{owner}", "");
    }

    /** Largest amount a single buy order may ask for. */
    public int maxBuyAmount() { return Math.max(1, getInt("stalls.max-buy-amount", 4096)); }

    public int promptTimeoutSeconds() { return Math.max(10, getInt("prompt.timeout-seconds", 60)); }

    /** Upper bound for any single price, so a typo cannot overflow sums. */
    public long priceMax() { return Math.max(1L, getLong("price.max", 100_000_000L)); }

    public boolean antiDumpEnabled() { return getBool("anti-dump.enabled", true); }

    /**
     * Lowest allowed unit price for an item (material name upper-case), 0 = no floor. The higher of
     * the config anti-dump floor and the admin bound set with {@code /lsa price bounds}.
     */
    public long minPrice(String itemKey) {
        if (itemKey == null) return 0L;
        long floor = 0L;
        ConfigurationSection s = root();
        if (antiDumpEnabled() && s != null) {
            floor = Math.max(0L, s.getLong("anti-dump.min-prices." + itemKey.toUpperCase(Locale.ROOT), 0L));
        }
        var prices = plugin.getPricesManager();
        if (prices != null) floor = Math.max(floor, prices.getBounds(itemKey).min());
        return floor;
    }

    /** Highest allowed unit price for an item: the global cap, tightened by an admin bound if one is set. */
    public long maxPrice(String itemKey) {
        long cap = priceMax();
        var prices = plugin.getPricesManager();
        if (itemKey != null && prices != null) {
            long bound = prices.getBounds(itemKey).max();
            if (bound > 0) cap = Math.min(cap, bound);
        }
        return cap;
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

    // ----- robbery -----
    public boolean robberyEnabled() { return getBool("robbery.enabled", true); }
    public double robberyBaseChance() { return getDouble("robbery.base-chance", 0.01); }
    public double robberyStep() { return getDouble("robbery.step", 0.005); }
    public double robberyChanceCap() { return getDouble("robbery.chance-cap", 0.20); }
    public int robberyAttemptsPerDay() { return Math.max(1, getInt("robbery.attempts-per-day", 12)); }
    public int robberyDayResetHour() { return getInt("robbery.day-reset-hour", 4); }
    public int robberyCoAttackWindowMinutes() { return Math.max(1, getInt("robbery.co-attack-window-minutes", 15)); }
    public double robberyMaxCombined() { return getDouble("robbery.max-combined-chance", 0.90); }
    public int robberyMaxPerPointPerDay() { return Math.max(1, getInt("robbery.max-robberies-per-point-per-day", 2)); }
    public int robberyMaxPerPlayerPerDay() { return Math.max(1, getInt("robbery.max-robberies-per-day", 3)); }
    public double robberyCoinsChance() { return Math.min(1.0, Math.max(0.0, getDouble("robbery.coins-chance", 0.5))); }
    public double robberyMaxTillPercent() { return getDouble("robbery.max-till-percent", 15.0); }
    public double robberyMaxStockPercent() { return getDouble("robbery.max-stock-percent", 10.0); }
    public int robberyMaxItems() { return Math.max(1, getInt("robbery.max-items", 8)); }
    public int robberyCooldownHours() { return Math.max(0, getInt("robbery.robbery-cooldown-hours", 6)); }
    public int robberyHostilityMinutes() { return Math.max(0, getInt("robbery.hostility-minutes", 30)); }
    public int robberyReputationPenalty() { return Math.max(0, getInt("robbery.robbery-reputation-penalty", 5)); }

    // ----- guard -----
    public boolean guardEnabled() { return getBool("guard.enabled", true); }
    public long guardSalary() { return Math.max(0L, getLong("guard.salary", 800L)); }
    public int guardSalaryPeriodHours() { return Math.max(1, getInt("guard.salary-period-hours", 24)); }
    public int guardHarassmentLimit() { return Math.max(1, getInt("guard.harassment-limit", 5)); }
    public int guardHarassmentWindowMinutes() { return Math.max(1, getInt("guard.harassment-window-minutes", 10)); }
    public int guardHarassmentBanMinutes() { return Math.max(1, getInt("guard.harassment-ban-minutes", 30)); }

    /** Where trouble-makers are sent from a stall; {@code null} when not configured or the world is missing. */
    public org.bukkit.Location exitLocation() {
        ConfigurationSection s = root();
        if (s == null || !s.isConfigurationSection("exit")) return null;
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(s.getString("exit.world", "world"));
        if (world == null) return null;
        return new org.bukkit.Location(world, s.getDouble("exit.x"), s.getDouble("exit.y", 64), s.getDouble("exit.z"),
                (float) s.getDouble("exit.yaw", 0), (float) s.getDouble("exit.pitch", 0));
    }
}
