package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Хранилище цен скупщика в отдельном файле prices.yml (вынесено из config.yml, чтобы
 * balance-правки не мешались с общими настройками плагина):
 *
 * <ul>
 *     <li>{@code common:} — обычные предметы, цена по материалу (то, что раньше жило
 *     в {@code prices-config} внутри config.yml).</li>
 *     <li>{@code rare:} — ручная метка редкости/цены поверх автоматического определения
 *     (см. {@link PriceCalculator#isRareItem}): {@code rare: true} принудительно уводит
 *     материал на аукцион, даже если авто-проверка (ItemRarity/зачарования) его не
 *     заметила; {@code rare: false} принудительно ИСКЛЮЧАЕТ материал из авто-определения,
 *     даже если он зачарован или несёт компонент редкости — например, если авто-проверка
 *     ошибочно считает какой-то предмет редким. {@code price}, если задана, — стартовая
 *     цена лота на аукционе вместо цены по формуле.</li>
 * </ul>
 *
 * Заполняется командами {@code /loveshopsadmin price} и {@code /loveshopsadmin rarity}.
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

    public int getCommonPrice(String material, int def) {
        return yaml.getInt("common." + material, def);
    }

    public void setCommonPrice(String material, int price) {
        yaml.set("common." + material.toUpperCase(Locale.ROOT), price);
        save();
    }

    public Optional<RareOverride> getRareOverride(String material) {
        String path = "rare." + material.toUpperCase(Locale.ROOT);
        if (!yaml.isConfigurationSection(path)) {
            return Optional.empty();
        }
        boolean rare = yaml.getBoolean(path + ".rare", true);
        Integer price = yaml.contains(path + ".price") ? yaml.getInt(path + ".price") : null;
        return Optional.of(new RareOverride(rare, price));
    }

    /** Устанавливает флаг редкости, сохраняя уже заданную цену (если была). */
    public void setRareFlag(String material, boolean rare) {
        String path = "rare." + material.toUpperCase(Locale.ROOT);
        yaml.set(path + ".rare", rare);
        save();
    }

    /**
     * Устанавливает цену предмета: если материал уже помечен как редкий — обновляет цену
     * лота в его override-записи, иначе задаёт обычную цену скупки.
     */
    public void setItemPrice(String material, int price) {
        String upper = material.toUpperCase(Locale.ROOT);
        if (getRareOverride(upper).isPresent()) {
            yaml.set("rare." + upper + ".price", price);
            save();
        } else {
            setCommonPrice(upper, price);
        }
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить prices.yml: " + e.getMessage());
        }
    }
}
