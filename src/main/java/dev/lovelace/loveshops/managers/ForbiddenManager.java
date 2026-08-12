package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Список материалов, запрещённых к продаже — скупщику, на барахолку и на аукцион (все три
 * канала берут начало в {@link BuyerManager#processSale}, аукцион дополнительно проверяется
 * в {@link AuctionManager#createAuction} на случай внешних вызовов вроде LoveBrew).
 * Отдельный файл forbidden.yml, заполняется командами
 * {@code /loveshopsadmin forbidden}/{@code allowed}.
 */
public final class ForbiddenManager {

    private final LoveShops plugin;
    private File file;
    private YamlConfiguration yaml;
    private final Set<String> forbidden = new HashSet<>();

    public ForbiddenManager(LoveShops plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "forbidden.yml");
        if (!file.exists()) {
            plugin.saveResource("forbidden.yml", false);
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        forbidden.clear();
        forbidden.addAll(yaml.getStringList("materials"));
    }

    public boolean isForbidden(Material material) {
        return forbidden.contains(material.name());
    }

    public boolean isForbidden(ItemStack item) {
        return item != null && !item.getType().isAir() && isForbidden(item.getType());
    }

    /** @return {@code true}, если материал был добавлен (не был запрещён раньше). */
    public boolean forbid(Material material) {
        boolean added = forbidden.add(material.name().toUpperCase(Locale.ROOT));
        if (added) {
            save();
        }
        return added;
    }

    /** @return {@code true}, если материал был убран из списка (был запрещён). */
    public boolean allow(Material material) {
        boolean removed = forbidden.remove(material.name().toUpperCase(Locale.ROOT));
        if (removed) {
            save();
        }
        return removed;
    }

    private void save() {
        yaml.set("materials", new ArrayList<>(forbidden));
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить forbidden.yml: " + e.getMessage());
        }
    }
}
