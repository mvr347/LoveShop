package dev.lovelace.loveshops.market.feudal;

import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.npc.NpcLookup;
import dev.lovelace.loveshops.npc.NpcTags;
import dev.lovelace.loveshops.npc.NpcText;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The landlord NPC ("Феодал"): the NPC players rent trade points from, hand them back to and hire the guard
 * at. The NPC is tagged through the NPC provider, so it is kept over restarts and can always be found again
 * (there is no table of ids to go stale).
 */
public final class FeudalService {

    public static final String KEY_FEUDAL = NpcTags.FEUDAL;

    private final LoveShops plugin;
    /** The name-based adoption of a pre-tag landlord is attempted once per run. */
    private volatile boolean legacyChecked;

    public FeudalService(LoveShops plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return plugin.getNpcBridge().available();
    }

    public boolean isFeudal(NpcHandle npc) {
        return npc != null && npc.tag(KEY_FEUDAL).isPresent();
    }

    private List<NpcHandle> all() {
        List<NpcHandle> found = new ArrayList<>();
        if (!available()) return found;
        try {
            found.addAll(plugin.getNpcBridge().withTag(KEY_FEUDAL));
            if (!legacyChecked) {
                legacyChecked = true;
                if (found.isEmpty() && plugin.getNpcBridge().ownsProvider("citizens")) found.addAll(adoptLegacy());
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Поиск Феодала не удался: " + t.getMessage());
        }
        return found;
    }

    /**
     * One-time fallback for a landlord made before tags moved into the provider's own storage: it left no row
     * to look up (that is the point of tagging it), so the only trace is its name. An NPC named exactly like the
     * configured landlord and carrying no LoveShops identity is tagged as the landlord. Names are not used
     * for anything else.
     */
    private List<NpcHandle> adoptLegacy() {
        List<NpcHandle> adopted = new ArrayList<>();
        String name = NpcText.plainAmp(plugin.getMarketConfig().feudalName());
        if (name.isBlank()) return adopted;
        for (NpcHandle npc : plugin.getNpcBridge().all()) {
            if (!NpcLookup.isLegacyLandlord(npc, name)) continue;
            npc.tag(KEY_FEUDAL, "true");
            adopted.add(npc);
        }
        if (!adopted.isEmpty()) {
            plugin.getLogger().info("Феодал: найдено NPC со старой меткой по имени «" + name + "» и помечено заново: " + adopted.size());
        }
        return adopted;
    }

    /** Creates a landlord at {@code loc}; {@code false} when no NPC provider is available. */
    public boolean create(Location loc) {
        if (loc == null || loc.getWorld() == null || !available()) return false;
        try {
            NpcSpec spec = NpcSpec.at(NpcText.amp(plugin.getMarketConfig().feudalName()), loc)
                    .withTags(Map.of(KEY_FEUDAL, "true"))
                    .withLookClose(plugin.getMarketConfig().npcLookClose());
            String skin = plugin.getMarketConfig().feudalSkin();
            if (skin != null && !skin.isBlank()) spec = spec.withSkinName(skin);
            return plugin.getNpcBridge().create(spec).isPresent();
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать Феодала: " + t.getMessage());
            return false;
        }
    }

    /** Removes every landlord; returns how many were removed. */
    public int removeAll() {
        int removed = 0;
        for (NpcHandle npc : all()) {
            try {
                if (plugin.getNpcBridge().remove(npc.key())) removed++;
            } catch (Throwable t) {
                plugin.getLogger().warning("Феодал #" + npc.key() + " не удалён: " + t.getMessage());
            }
        }
        return removed;
    }

    public Optional<Location> location() {
        for (NpcHandle npc : all()) {
            Location loc = npc.location();
            if (loc != null && loc.getWorld() != null) return Optional.of(loc);
        }
        return Optional.empty();
    }

    /** A right click on an NPC: opens the landlord menu when it is one. */
    public boolean handleClick(Player player, NpcHandle npc) {
        // The first click of a run also looks for a landlord made before tags moved to the provider.
        if (!isFeudal(npc) && !legacyChecked) all();
        if (!isFeudal(npc)) return false;
        new FeudalListGui(plugin, player).open();
        return true;
    }
}
