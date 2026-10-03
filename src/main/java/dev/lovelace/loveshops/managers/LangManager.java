package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Map;

public class LangManager {

    private final LoveShops plugin;
    private FileConfiguration langConfig;

    public LangManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public void loadLang() {
        File langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        this.langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    public String getRaw(String path, String defaultValue) {
        if (langConfig == null) return defaultValue;
        return langConfig.getString(path, defaultValue);
    }

    /** A player-facing message: no plugin prefix (the prefix is for admin commands only). */
    public Component getMessage(String path, String defaultValue, Map<String, String> placeholders) {
        return build("", path, defaultValue, placeholders);
    }

    /** An admin-command message: carries the plugin prefix. */
    public Component getAdminMessage(String path, String defaultValue, Map<String, String> placeholders) {
        return build(getRaw("prefix", ""), path, defaultValue, placeholders);
    }

    public Component getAdminMessage(String path, String defaultValue) {
        return getAdminMessage(path, defaultValue, null);
    }

    private Component build(String prefix, String path, String defaultValue, Map<String, String> placeholders) {
        String raw = getRaw(path, defaultValue);
        String full = prefix + raw;

        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                full = full.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return MessageUtils.parse(full);
    }

    public Component getMessage(String path, String defaultValue) {
        return getMessage(path, defaultValue, null);
    }
}
