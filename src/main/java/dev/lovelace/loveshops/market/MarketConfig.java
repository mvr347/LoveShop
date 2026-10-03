package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.gui.MarketLayout;
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

    /** Plain number or money text ("3i"); no price index (counters, limits). */
    private long getLong(String path, long def) {
        return dev.lovelace.loveshops.utils.Money.raw(root(), path, def);
    }

    /** A price-like money value: number or money text, with LoveCore's price index applied. */
    private long getMoney(String path, long def) {
        return dev.lovelace.loveshops.utils.Money.scaled(root(), path, def);
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

    // ----- id sign, landlord ("Феодал") -----
    public java.util.List<String> idSignLines() {
        ConfigurationSection s = root();
        java.util.List<String> lines = s == null ? java.util.List.of() : s.getStringList("id-sign.lines");
        return lines.isEmpty() ? java.util.List.of("&6Торговая точка", "&f{id}", "{status}", "&7Аренда у Феодала") : lines;
    }

    /** Weekly rent given to a point made by the wizard when the admin does not type a price. */
    public long defaultRentPrice() { return Math.max(0L, getMoney("feudal.default-rent-price", 3_000L)); }

    public String feudalName() { return getString("npc.feudal-name", "&6Феодал"); }
    public String feudalSkin() { return getString("npc.feudal-skin", ""); }

    /** Share (percent) of the unspent rent given back when a tenant returns the point to the landlord. */
    public int feudalRefundPercent() { return Math.max(0, Math.min(100, getInt("feudal.refund-percent", 50))); }

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
    /** Highest level: the one that fills the biggest (54-slot) menu; {@code stalls.max-level} may only lower it. */
    public int maxLevel() {
        int full = UpgradeMath.maxLevel(MarketLayout.maxShelves(), Math.max(baseSellSlots(), baseBuySlots()));
        int cap = getInt("stalls.max-level", 0);
        return cap > 0 ? Math.min(cap, full) : full;
    }
    public long confirmThreshold() { return Math.max(0L, getMoney("stalls.confirm-threshold", 1_000L)); }
    public int pendingTimeoutSeconds() { return Math.max(5, getInt("stalls.pending-timeout-seconds", 30)); }

    /** Name shown above the stall NPC; {@code {owner}} is the tenant's name. Legacy {@code &} codes. */
    /** The old default ("Trade point" + owner) is replaced by the new one, so existing config.yml files get the id too. */
    public String npcNameFormat() {
        String format = getString("npc.name-format", NEW_NAME_FORMAT);
        return OLD_NAME_FORMAT.equals(format) ? NEW_NAME_FORMAT : format;
    }
    private static final String OLD_NAME_FORMAT = "&6Торговая точка\n&f{owner}";
    private static final String NEW_NAME_FORMAT = "&6Торговая точка №{id}\n&fВладелец: &e{owner}";
    public String statusOpen() { return getString("npc.status-open", "&aОткрыто"); }
    public String statusClosed() { return getString("npc.status-closed", getString("npc.status.closed", "&cЗакрыто")); }
    public String statusRobbed() { return getString("npc.status-robbed", getString("npc.status.robbed", "&4[ОГРАБЛЕНО]")); }
    public String statusEmpty() { return getString("npc.status-empty", "&7Нет лотов"); }
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
    public long guardCostPerDay() { return Math.max(0L, getMoney("guard.cost-per-day", 200L)); }
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

    /** The picker starts on the smallest coin worth at least this ({@code price.start-unit}, default "1i" = 100). */
    public long priceStartUnit() { return Math.max(1L, getLong("price.start-unit", 100L)); }

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
            floor = Math.max(0L, dev.lovelace.loveshops.utils.Money.scaled(s,
                    "anti-dump.min-prices." + itemKey.toUpperCase(Locale.ROOT), 0L));
            // 2026-10-03: the floor also follows the LoveCore price model (anti-dump.min-percent-of-model of an item's value),
            // so every priced item is protected, not just the two listed in min-prices.
            double percent = s.getDouble("anti-dump.min-percent-of-model", 0.0);
            if (percent > 0) {
                org.bukkit.Material material = org.bukkit.Material.matchMaterial(itemKey);
                var model = dev.lovelace.loveshops.utils.Money.modelValue(material);
                if (model.isPresent()) {
                    floor = Math.max(floor, dev.lovelace.loveshops.utils.Money.percentOf(model.getAsLong(), percent));
                }
            }
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
    /**
     * 2026-10-03: the upgrade price is a plain money value ({@code stalls.upgrade-cost}, default "20i" = 2 000) and a
     * step ({@code stalls.upgrade-cost-step}, "20i"): level 1 -> 2 costs the base, every next one the step more.
     * It used to be "N coins of the biggest denomination", which jumped 20x when the denominations changed.
     */
    public long upgradeCost() { return Math.max(1L, getMoney("stalls.upgrade-cost", 2_000L)); }
    public long upgradeCostStep() { return Math.max(0L, getMoney("stalls.upgrade-cost-step", 2_000L)); }

    // ----- rating -----
    public long ratingMinTrade() { return Math.max(0L, getMoney("rating.min-trade-amount", 1_000L)); }
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
    public long guardSalary() { return Math.max(0L, getMoney("guard.salary", 800L)); }
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
