package dev.lovelace.loveshops.integration;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

/**
 * Bridge into Citizens for the admin "look at NPC, run a bind command" workflow used by
 * {@code /loveshopsadmin npc create|delete}. LoveShops already compiles directly against the
 * Citizens API (unlike LoveTweaks/LoveClans, which reflect into it because Citizens is truly
 * optional for them) - {@link dev.lovelace.loveshops.managers.NpcManager} does the same, so this
 * class matches that existing style rather than adding a second, reflection-based convention.
 * <p>
 * Mirrors the {@code CitizensIntegration.lookedAtNpc(player, distance)} pattern already
 * established in LoveClans ({@code ClansAdminCommand#createNpc}) and LoveTweaks
 * ({@code LoveTweaksAdminCommand#handleHeraldBind}): the admin creates/positions the NPC with
 * Citizens' own commands first, then binds it to a plugin role by looking at it - the plugin
 * never creates or destroys the underlying Citizens NPC itself.
 */
public final class CitizensIntegration {

    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    /** Citizens NPC id + name, already unwrapped from the Citizens handle for admin binding. */
    public record NpcRef(int id, String name) {}

    /**
     * The Citizens NPC the player is currently looking at, or {@code null} if Citizens isn't
     * running, nothing is in range, or the entity hit isn't a Citizens NPC at all.
     */
    public NpcRef lookedAtNpc(Player player, double distance) {
        if (!isAvailable() || player == null) return null;
        // rayTraceEntities takes an int range, so a fractional configured distance is rounded
        // up rather than truncated - truncating a 6.0 down to 5 would shrink the intended range.
        RayTraceResult trace = player.rayTraceEntities((int) Math.ceil(distance));
        Entity hit = trace == null ? null : trace.getHitEntity();
        if (hit == null || !CitizensAPI.getNPCRegistry().isNPC(hit)) return null;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(hit);
        return npc == null ? null : new NpcRef(npc.getId(), npc.getName());
    }

    /** Resolves a bound Citizens NPC by its Citizens id (stored in {@code shops_npcs.citizens_id}). */
    public NPC byId(int citizensId) {
        if (!isAvailable()) return null;
        return CitizensAPI.getNPCRegistry().getById(citizensId);
    }
}
