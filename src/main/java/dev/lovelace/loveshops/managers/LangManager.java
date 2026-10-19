package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LangManager {

    private final LoveShops plugin;
    private FileConfiguration langConfig;
    /** The lang.yml of the jar: the fallback for every key the server's own file lacks. */
    private FileConfiguration bundled;

    public LangManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public void loadLang() {
        File langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }
        this.langConfig = YamlConfiguration.loadConfiguration(langFile);
        this.bundled = loadBundled();
    }

    private FileConfiguration loadBundled() {
        try (InputStream in = plugin.getResource("lang.yml")) {
            return in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось прочитать встроенный lang.yml: " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    public String getRaw(String path, String defaultValue) {
        if (langConfig == null) return defaultValue;
        String value = langConfig.getString(path);
        if (value == null && bundled != null) value = bundled.getString(path);
        return value != null ? value : defaultValue;
    }

    /**
     * A GUI text from lang.yml with {@code {name}} placeholders replaced ({@code kv} = name, value, ...). A missing key
     * shows as a red {@code [path]} so it is noticed in game instead of an empty line.
     */
    public String text(String path, String... kv) {
        return apply(getRaw(path, "<red>[" + path + "]</red>"), kv);
    }

    /** All lines of a lore text from lang.yml (a single string gives one line); empty strings are blank lines. */
    public List<String> lines(String path, String... kv) {
        List<String> raw = null;
        for (FileConfiguration cfg : new FileConfiguration[]{langConfig, bundled}) {
            if (cfg == null) continue;
            if (cfg.isList(path)) {
                raw = new ArrayList<>(cfg.getStringList(path));
                break;
            }
            String single = cfg.getString(path);
            if (single != null) {
                raw = List.of(single);
                break;
            }
        }
        if (raw == null) return List.of("<red>[" + path + "]</red>");
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) out.add(apply(line, kv));
        return out;
    }

    private static String apply(String text, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            text = text.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return text;
    }

    /** The {@code style.*} texts for {@link dev.lovelace.loveshops.utils.LoreBlocks}. */
    public dev.lovelace.loveshops.utils.LoreBlocks.Style style() {
        return path -> getRaw(path, null);
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
