package dev.lovelace.loveshops.textures;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * Читает base64-текстуры голов из heads.yml, чтобы текстуру каждой кнопки можно было
 * заменить без пересборки плагина. Значения из Java-констант остаются запасным вариантом
 * на случай, если ключ отсутствует в heads.yml.
 */
final class HeadsConfig {
    private static volatile YamlConfiguration config;

    private HeadsConfig() {
    }

    static String get(String key, String fallback) {
        YamlConfiguration yaml = config();
        if (yaml == null) {
            return fallback;
        }
        return yaml.getString(key, fallback);
    }

    private static YamlConfiguration config() {
        YamlConfiguration loaded = config;
        if (loaded == null) {
            synchronized (HeadsConfig.class) {
                loaded = config;
                if (loaded == null) {
                    loaded = load();
                    config = loaded;
                }
            }
        }
        return loaded;
    }

    private static YamlConfiguration load() {
        try {
            JavaPlugin plugin = JavaPlugin.getProvidingPlugin(HeadsConfig.class);
            File file = new File(plugin.getDataFolder(), "heads.yml");
            if (!file.exists()) {
                plugin.saveResource("heads.yml", false);
            }
            return YamlConfiguration.loadConfiguration(file);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return null;
        }
    }
}
