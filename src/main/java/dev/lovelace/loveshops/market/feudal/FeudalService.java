package dev.lovelace.loveshops.market.feudal;

import dev.lovelace.loveshops.LoveShops;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The landlord NPC ("Феодал"): the Citizens NPC players rent trade points from, hand them back to
 * and hire the guard at. The NPC is tagged with persistent data, so Citizens keeps it over restarts
 * and it can always be found again (there is no table of ids to go stale).
 */
public final class FeudalService {

    public static final String KEY_FEUDAL = "loveshops_feudal";

    private final LoveShops plugin;

    public FeudalService(LoveShops plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    public boolean isFeudal(NPC npc) {
        return npc != null && npc.data().has(KEY_FEUDAL);
    }

    private List<NPC> all() {
        List<NPC> found = new ArrayList<>();
        if (!available()) return found;
        try {
            for (NPC npc : CitizensAPI.getNPCRegistry()) {
                if (isFeudal(npc)) found.add(npc);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Поиск Феодала не удался: " + t.getMessage());
        }
        return found;
    }

    /** Creates a landlord at {@code loc}; {@code false} when Citizens is not available. */
    public boolean create(Location loc) {
        if (!available() || loc == null || loc.getWorld() == null) return false;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER,
                    ChatColor.translateAlternateColorCodes('&', plugin.getMarketConfig().feudalName()));
            npc.data().setPersistent(KEY_FEUDAL, true);
            String skin = plugin.getMarketConfig().feudalSkin();
            if (skin != null && !skin.isBlank()) npc.getOrAddTrait(SkinTrait.class).setSkinName(skin);
            if (plugin.getMarketConfig().npcLookClose()) npc.getOrAddTrait(LookClose.class).lookClose(true);
            npc.spawn(loc);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать Феодала: " + t.getMessage());
            return false;
        }
    }

    /** Removes every landlord; returns how many were removed. */
    public int removeAll() {
        int removed = 0;
        for (NPC npc : all()) {
            try {
                if (npc.isSpawned()) npc.despawn();
                npc.destroy();
                removed++;
            } catch (Throwable t) {
                plugin.getLogger().warning("Феодал #" + npc.getId() + " не удалён: " + t.getMessage());
            }
        }
        return removed;
    }

    public Optional<Location> location() {
        for (NPC npc : all()) {
            Location loc = npc.isSpawned() ? npc.getEntity().getLocation() : npc.getStoredLocation();
            if (loc != null && loc.getWorld() != null) return Optional.of(loc);
        }
        return Optional.empty();
    }

    /** A right click on a Citizens NPC: opens the landlord menu when it is one. */
    public boolean handleClick(Player player, NPC npc) {
        if (!isFeudal(npc)) return false;
        new FeudalListGui(plugin, player).open();
        return true;
    }
}
