package dev.lovelace.loveshops.managers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * Bukkit's copyDefaults only adds missing keys, so a text that was reworded in the jar never reaches a server's
 * existing lang.yml. Each {@code lang-version} bump lists the parts that must be taken from the jar again; the caller
 * keeps a backup of the old file (see {@link LangManager#loadLang()}).
 */
public final class LangMigrator {

    public static final String VERSION_KEY = "lang-version";

    /**
     * A part of lang.yml to overwrite: a whole section, or only those direct children of it whose key starts with
     * {@code keyPrefix} (the market texts share one section with chat messages that the admin may have edited).
     */
    record Root(String path, String keyPrefix) {
        static Root section(String path) {
            return new Root(path, "");
        }

        static Root prefixed(String path, String keyPrefix) {
            return new Root(path, keyPrefix);
        }
    }

    /** Parts overwritten when migrating to version N (index = N - 2). */
    private static final List<List<Root>> OVERWRITE_BY_VERSION = List.of(
            // v2: GUI lore and names in the gui-gen-6 standard - style.*, the NPC menus (gui.*) and the market menus (market.gui-*)
            List.of(Root.section("style"), Root.section("gui"), Root.prefixed("market", "gui-"))
    );

    private LangMigrator() {
    }

    public static int latestVersion() {
        return OVERWRITE_BY_VERSION.size() + 1;
    }

    /** True when the server file was older than the jar and has been changed. */
    public static boolean migrate(FileConfiguration server, FileConfiguration bundled) {
        int current = server.getInt(VERSION_KEY, 1);
        int target = Math.min(bundled.getInt(VERSION_KEY, 1), latestVersion());
        if (current >= target) {
            return false;
        }
        for (int version = current + 1; version <= target; version++) {
            for (Root root : OVERWRITE_BY_VERSION.get(version - 2)) {
                overwrite(server, bundled, root);
            }
        }
        server.set(VERSION_KEY, target);
        return true;
    }

    private static void overwrite(FileConfiguration server, FileConfiguration bundled, Root root) {
        if (root.keyPrefix().isEmpty()) {
            overwriteSection(server, bundled, root.path());
            return;
        }
        // only the prefixed children: drop the server's, then take the jar's
        ConfigurationSection serverSection = server.getConfigurationSection(root.path());
        if (serverSection != null) {
            for (String key : List.copyOf(serverSection.getKeys(false))) {
                if (key.startsWith(root.keyPrefix())) {
                    server.set(root.path() + "." + key, null);
                }
            }
        }
        ConfigurationSection jarSection = bundled.getConfigurationSection(root.path());
        if (jarSection == null) {
            return;
        }
        for (String key : jarSection.getKeys(false)) {
            if (key.startsWith(root.keyPrefix())) {
                copy(server, bundled, root.path() + "." + key);
            }
        }
    }

    private static void overwriteSection(FileConfiguration server, FileConfiguration bundled, String path) {
        Object value = bundled.get(path);
        if (value instanceof ConfigurationSection section) {
            server.set(path, null);
            for (String key : section.getKeys(true)) {
                Object leaf = section.get(key);
                if (!(leaf instanceof ConfigurationSection)) {
                    copy(server, bundled, path + "." + key);
                }
            }
        } else if (value != null) {
            copy(server, bundled, path);
        }
    }

    /** Copies one value together with the comment the jar has above it. */
    private static void copy(FileConfiguration server, FileConfiguration bundled, String path) {
        server.set(path, bundled.get(path));
        List<String> comments = bundled.getComments(path);
        if (!comments.isEmpty()) {
            server.setComments(path, comments);
        }
    }
}
