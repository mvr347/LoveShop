package dev.lovelace.loveshops.utils;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Bukkit never writes new default keys into a server's existing config.yml, and getKeys() on a section does not
 * merge the jar's defaults either, so sections added by an update (crate variations, new switches) were invisible
 * to the code on servers that already had a config. This copies only the paths the server file lacks.
 */
public final class ConfigBackfill {

    private ConfigBackfill() {
    }

    /**
     * @param atomic section paths the owner edits as a whole (loot tables): when the server file has one, none of
     *               its keys are added, otherwise an item the owner deliberately removed would come back
     * @return how many keys were added
     */
    public static int backfill(ConfigurationSection server, ConfigurationSection bundled, java.util.Set<String> atomic, String... roots) {
        int added = 0;
        for (String root : roots) {
            ConfigurationSection section = bundled.getConfigurationSection(root);
            if (section == null) continue;
            for (String key : section.getKeys(true)) {
                String path = root + "." + key;
                if (section.isConfigurationSection(key)) continue;
                if (atomic.stream().anyMatch(a -> path.startsWith(a + ".") && server.isConfigurationSection(a))) continue;
                if (!server.contains(path, true)) {
                    server.set(path, section.get(key));
                    added++;
                }
            }
        }
        return added;
    }
}
