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

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить prices.yml: " + e.getMessage());
        }
    }
}
