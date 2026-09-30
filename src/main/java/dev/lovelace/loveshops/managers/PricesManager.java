package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Хранилище цен для различных торговцев и NPC в отдельном файле prices.yml:
 *
 * <ul>
 *     <li>{@code buyer:} — цены скупки у игроков Скупщиком (плюс fallback на {@code common:}).</li>
 *     <li>{@code seller:} — цены/наценки Барахолки.</li>
 *     <li>{@code war_merchant:} — цены Военного торговца.</li>
 *     <li>{@code wanderer:} — базовые цены предметов Странника.</li>
 *     <li>{@code auctioneer:} / {@code rare:} — аукционные цены и метки редкости.</li>
 * </ul>
 *
 * Поддерживает тонкую настройку цен каждому торговцу отдельно или всем сразу:
 * {@code /loveshopsadmin price <buyer|seller|war_merchant|wanderer|auctioneer|all> <цена>}
 */
public final class PricesManager {

    public record RareOverride(boolean rare, Integer price) {}

    private final LoveShops plugin;
    private File file;
    private YamlConfiguration yaml;

    public PricesManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) {
            plugin.saveResource("prices.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    public int getBuyerPrice(String material, int def) {
        String upper = material.toUpperCase(Locale.ROOT);
        if (yaml.contains("buyer." + upper)) {
            return yaml.getInt("buyer." + upper, def);
        }
        return yaml.getInt("common." + upper, def);
    }

    public int getCommonPrice(String material, int def) {
        return getBuyerPrice(material, def);
    }

    public void setBuyerPrice(String material, int price) {
        String upper = material.toUpperCase(Locale.ROOT);
        yaml.set("buyer." + upper, price);
        yaml.set("common." + upper, price);
        save();
    }

    public void setCommonPrice(String material, int price) {
        setBuyerPrice(material, price);
    }

    public int getSellerPrice(String material, int def) {
        String upper = material.toUpperCase(Locale.ROOT);
        if (yaml.contains("seller." + upper)) {
            return yaml.getInt("seller." + upper, def);
        }
        return def;
    }

    public void setSellerPrice(String material, int price) {
        String upper = material.toUpperCase(Locale.ROOT);
        yaml.set("seller." + upper, price);
        save();
    }

    public int getWarMerchantPrice(String material, int def) {
        String upper = material.toUpperCase(Locale.ROOT);
        if (yaml.contains("war_merchant." + upper)) {
            return yaml.getInt("war_merchant." + upper, def);
        }
        if (yaml.contains("war-merchant." + upper)) {
            return yaml.getInt("war-merchant." + upper, def);
        }
        return def;
    }

    public void setWarMerchantPrice(String material, int price) {
        String upper = material.toUpperCase(Locale.ROOT);
        yaml.set("war_merchant." + upper, price);
        save();
    }

    public int getWandererPrice(String idOrMaterial, int def) {
        if (idOrMaterial == null) return def;
        String raw = idOrMaterial.trim();
        if (yaml.contains("wanderer." + raw)) {
            return yaml.getInt("wanderer." + raw, def);
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (yaml.contains("wanderer." + upper)) {
            return yaml.getInt("wanderer." + upper, def);
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (yaml.contains("wanderer." + lower)) {
            return yaml.getInt("wanderer." + lower, def);
        }
        return def;
    }

    public void setWandererPrice(String idOrMaterial, int price) {
        if (idOrMaterial == null) return;
        yaml.set("wanderer." + idOrMaterial.trim(), price);
        save();
    }

    public Optional<RareOverride> getRareOverride(String material) {
        String upper = material.toUpperCase(Locale.ROOT);
        String path = "auctioneer." + upper;
        if (!yaml.isConfigurationSection(path)) {
            path = "rare." + upper;
        }
        if (!yaml.isConfigurationSection(path)) {
            return Optional.empty();
        }
        boolean rare = yaml.getBoolean(path + ".rare", true);
        Integer price = yaml.contains(path + ".price") ? yaml.getInt(path + ".price") : null;
        return Optional.of(new RareOverride(rare, price));
    }

    public void setRareFlag(String material, boolean rare) {
        String upper = material.toUpperCase(Locale.ROOT);
        yaml.set("auctioneer." + upper + ".rare", rare);
        yaml.set("rare." + upper + ".rare", rare);
        save();
    }

    public void setItemPrice(String material, int price) {
        String upper = material.toUpperCase(Locale.ROOT);
        if (getRareOverride(upper).isPresent()) {
            yaml.set("auctioneer." + upper + ".price", price);
            yaml.set("rare." + upper + ".price", price);
            save();
        } else {
            setBuyerPrice(upper, price);
        }
    }

    /**
     * Универсальная установка цены для конкретного NPC или для всех сразу.
     *
     * @param targetNpc "buyer", "seller", "war_merchant", "wanderer", "auctioneer" или "all"
     * @param itemKey   материал или id предмета
     * @param price     новая цена
     * @return список секций, в которых цена была обновлена
     */
    public List<String> setNpcPrice(String targetNpc, String itemKey, int price) {
        List<String> affected = new ArrayList<>();
        String normalizedTarget = targetNpc.toLowerCase(Locale.ROOT).replace('-', '_');
        String upper = itemKey.toUpperCase(Locale.ROOT);

        boolean isAll = normalizedTarget.equals("all");

        if (isAll || normalizedTarget.equals("buyer") || normalizedTarget.equals("common")) {
            yaml.set("buyer." + upper, price);
            yaml.set("common." + upper, price);
            affected.add("Скупщик (buyer)");
        }
        if (isAll || normalizedTarget.equals("seller")) {
            yaml.set("seller." + upper, price);
            affected.add("Барахолка (seller)");
        }
        if (isAll || normalizedTarget.equals("war_merchant") || normalizedTarget.equals("war")) {
            yaml.set("war_merchant." + upper, price);
            affected.add("Военный торговец (war_merchant)");
        }
        if (isAll || normalizedTarget.equals("wanderer")) {
            yaml.set("wanderer." + itemKey, price);
            yaml.set("wanderer." + upper, price);
            affected.add("Странник (wanderer)");
        }
        if (isAll || normalizedTarget.equals("auctioneer") || normalizedTarget.equals("rare") || normalizedTarget.equals("auction")) {
            yaml.set("auctioneer." + upper + ".price", price);
            yaml.set("auctioneer." + upper + ".rare", true);
            yaml.set("rare." + upper + ".price", price);
            yaml.set("rare." + upper + ".rare", true);
            affected.add("Аукцион (auctioneer)");
        }

        save();
        return affected;
    }

    // ===== Generic access for the admin price commands =====

    /** Merchants whose prices live in prices.yml. */
    public static final List<String> TARGETS = List.of("buyer", "seller", "war_merchant", "wanderer", "auctioneer");

    public static String normalizeTarget(String raw) {
        if (raw == null) return null;
        String t = raw.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (t) {
            case "common" -> "buyer";
            case "war" -> "war_merchant";
            case "rare", "auction" -> "auctioneer";
            default -> TARGETS.contains(t) ? t : null;
        };
    }

    private String keyOf(String target, String item) {
        return "wanderer".equals(target) ? item.trim() : item.trim().toUpperCase(Locale.ROOT);
    }

    /** The price written in prices.yml for this merchant and item, if any. */
    public Optional<Integer> peekOverride(String target, String item) {
        String key = keyOf(target, item);
        String path;
        switch (target) {
            case "buyer" -> path = yaml.contains("buyer." + key) ? "buyer." + key : (yaml.contains("common." + key) ? "common." + key : null);
            case "seller" -> path = yaml.contains("seller." + key) ? "seller." + key : null;
            case "war_merchant" -> path = yaml.contains("war_merchant." + key) ? "war_merchant." + key
                    : (yaml.contains("war-merchant." + key) ? "war-merchant." + key : null);
            case "wanderer" -> path = yaml.contains("wanderer." + key) ? "wanderer." + key : null;
            case "auctioneer" -> path = yaml.contains("auctioneer." + key + ".price") ? "auctioneer." + key + ".price"
                    : (yaml.contains("rare." + key + ".price") ? "rare." + key + ".price" : null);
            default -> path = null;
        }
        return path == null ? Optional.empty() : Optional.of(yaml.getInt(path));
    }

    /** Removes every stored price for the merchant and item. @return whether anything was removed */
    public boolean resetOverride(String target, String item) {
        String key = keyOf(target, item);
        boolean removed = false;
        List<String> paths = switch (target) {
            case "buyer" -> List.of("buyer." + key, "common." + key);
            case "seller" -> List.of("seller." + key);
            case "war_merchant" -> List.of("war_merchant." + key, "war-merchant." + key);
            case "wanderer" -> List.of("wanderer." + key);
            case "auctioneer" -> List.of("auctioneer." + key + ".price", "rare." + key + ".price");
            default -> List.of();
        };
        for (String path : paths) {
            if (yaml.contains(path)) {
                yaml.set(path, null);
                removed = true;
            }
        }
        if (removed) save();
        return removed;
    }

    /** All stored prices of a merchant, sorted by item. */
    public List<java.util.Map.Entry<String, Integer>> listOverrides(String target) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        switch (target) {
            case "buyer" -> {
                collectFlat(out, "common");
                collectFlat(out, "buyer");
            }
            case "seller" -> collectFlat(out, "seller");
            case "war_merchant" -> {
                collectFlat(out, "war-merchant");
                collectFlat(out, "war_merchant");
            }
            case "wanderer" -> collectFlat(out, "wanderer");
            case "auctioneer" -> {
                collectNested(out, "rare");
                collectNested(out, "auctioneer");
            }
            default -> { }
        }
        return new ArrayList<>(out.entrySet());
    }

    private void collectFlat(java.util.Map<String, Integer> out, String section) {
        var sec = yaml.getConfigurationSection(section);
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            if (sec.isInt(key)) out.put(key, sec.getInt(key));
        }
    }

    private void collectNested(java.util.Map<String, Integer> out, String section) {
        var sec = yaml.getConfigurationSection(section);
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            if (sec.contains(key + ".price")) out.put(key, sec.getInt(key + ".price"));
        }
    }

    // ===== Multipliers (percent, applied on top of the stored prices) =====

    public double getMultiplierPercent(String target) {
        return yaml.getDouble("multipliers." + target, 0.0);
    }

    public void setMultiplierPercent(String target, double percent) {
        yaml.set("multipliers." + target, percent == 0.0 ? null : percent);
        save();
    }

    /** Applies the merchant's multiplier to a price; never below 1. */
    public int applyMultiplier(String target, int price) {
        double percent = getMultiplierPercent(target);
        if (percent == 0.0) return price;
        return Math.max(1, (int) Math.round(price * (1.0 + percent / 100.0)));
    }

    // ===== Price bounds for the player market (0 = not set) =====

    public record Bounds(long min, long max) {
        public boolean isSet() { return min > 0 || max > 0; }
    }

    public Bounds getBounds(String material) {
        String upper = material.toUpperCase(Locale.ROOT);
        return new Bounds(yaml.getLong("bounds." + upper + ".min", 0L), yaml.getLong("bounds." + upper + ".max", 0L));
    }

    public void setBounds(String material, long min, long max) {
        String upper = material.toUpperCase(Locale.ROOT);
        if (min <= 0 && max <= 0) {
            yaml.set("bounds." + upper, null);
        } else {
            yaml.set("bounds." + upper + ".min", Math.max(0L, min));
            yaml.set("bounds." + upper + ".max", Math.max(0L, max));
        }
        save();
    }

    public java.util.Map<String, Bounds> listBounds() {
        java.util.Map<String, Bounds> out = new java.util.TreeMap<>();
        var sec = yaml.getConfigurationSection("bounds");
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) out.put(key, new Bounds(sec.getLong(key + ".min", 0L), sec.getLong(key + ".max", 0L)));
        return out;
    }

    // ===== Auction bid step override =====

    /** {@code type} is "percentage" or "fixed"; empty means "use config.yml". */
    public record BidStep(String type, int value) {}

    public Optional<BidStep> getBidStep() {
        if (!yaml.contains("auction.bid-step.type")) return Optional.empty();
        return Optional.of(new BidStep(yaml.getString("auction.bid-step.type", "percentage"), yaml.getInt("auction.bid-step.value", 5)));
    }

    public void setBidStep(String type, int value) {
        yaml.set("auction.bid-step.type", type);
        yaml.set("auction.bid-step.value", value);
        save();
    }

    public void clearBidStep() {
        yaml.set("auction.bid-step", null);
        save();
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить prices.yml: " + e.getMessage());
        }
    }
}
