package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.utils.MessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Market texts from {@code lang.yml} ({@code market.*}). The bundled lang.yml is the fallback for
 * every key the server's own file lacks, so a server that kept an older lang.yml never shows a
 * raw key. Text may be one string or a list (a random line is picked).
 */
public final class MarketMessages {

    private final LoveShops plugin;
    private volatile FileConfiguration server;
    private volatile FileConfiguration bundled;

    public MarketMessages(LoveShops plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        this.server = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        try (InputStream in = plugin.getResource("lang.yml")) {
            this.bundled = in == null ? new YamlConfiguration()
                    : YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            this.bundled = new YamlConfiguration();
            plugin.getLogger().warning("Не удалось прочитать встроенный lang.yml: " + e.getMessage());
        }
    }

    /** All variants of a text ({@code market.<key>}); empty when the key exists nowhere. */
    public List<String> variants(String key) {
        String path = "market." + key;
        List<String> list = fromConfig(server, path);
        if (list.isEmpty()) list = fromConfig(bundled, path);
        return list;
    }

    private static List<String> fromConfig(FileConfiguration cfg, String path) {
        if (cfg == null) return List.of();
        if (cfg.isList(path)) {
            List<String> out = new ArrayList<>();
            for (String s : cfg.getStringList(path)) if (s != null && !s.isEmpty()) out.add(s);
            return out;
        }
        String single = cfg.getString(path);
        return single == null || single.isEmpty() ? List.of() : List.of(single);
    }

    /** One raw line (random if several) with {@code {name}} placeholders replaced; {@code kv} = name, value, ... */
    public String raw(String key, String... kv) {
        List<String> variants = variants(key);
        if (variants.isEmpty()) return "<red>[" + key + "]</red>";
        String text = variants.size() == 1 ? variants.get(0) : variants.get(ThreadLocalRandom.current().nextInt(variants.size()));
        // {i_stall}, {i_guard}, ... - icons from MarketStyle, so a text never hard-codes a glyph.
        MarketStyle style = plugin.getMarketStyle();
        if (style != null && text.contains("{i_")) {
            for (MarketStyle.Icon icon : MarketStyle.Icon.values()) {
                text = text.replace("{i_" + icon.name().toLowerCase(java.util.Locale.ROOT) + "}", style.icon(icon));
            }
        }
        return apply(text, kv);
    }

    public static String apply(String text, String... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            text = text.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return text;
    }

    /** A line with the plugin prefix, ready to send. */
    public Component get(Player viewer, String key, String... kv) {
        String prefix = plugin.getLangManager().getRaw("prefix", "");
        return MessageUtils.parse(viewer, prefix + raw(key, kv));
    }

    /** A line without the prefix (GUI names, NPC speech that carries its own prefix). */
    public Component plain(Player viewer, String key, String... kv) {
        return MessageUtils.parse(viewer, raw(key, kv));
    }

    public void send(CommandSender to, String key, String... kv) {
        Player viewer = to instanceof Player p ? p : null;
        to.sendMessage(get(viewer, key, kv));
    }
}
