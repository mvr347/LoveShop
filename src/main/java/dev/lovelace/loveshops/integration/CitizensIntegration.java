package dev.lovelace.loveshops.integration;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

/**
 * Bridge into Citizens. LoveShops создаёт и уничтожает Citizens NPC сам
 * ({@code /loveshopsadmin npc create|delete}). {@code lookedAtNpc} остаётся для delete
 * (найти NPC взглядом) и совместимости.
 */
public final class CitizensIntegration {

    public boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    public record NpcRef(int id, String name) {}

    public NpcRef lookedAtNpc(Player player, double distance) {
        if (!isAvailable() || player == null) return null;
        RayTraceResult trace = player.rayTraceEntities((int) Math.ceil(distance));
        Entity hit = trace == null ? null : trace.getHitEntity();
        if (hit == null || !CitizensAPI.getNPCRegistry().isNPC(hit)) return null;
        NPC npc = CitizensAPI.getNPCRegistry().getNPC(hit);
        return npc == null ? null : new NpcRef(npc.getId(), npc.getName());
    }

    public NPC byId(int citizensId) {
        if (!isAvailable()) return null;
        return CitizensAPI.getNPCRegistry().getById(citizensId);
    }
}
