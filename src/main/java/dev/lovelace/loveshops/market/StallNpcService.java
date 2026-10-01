package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.HologramTrait;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Creates, finds and removes the Citizens NPCs of trade points: the trader and the guard. NPCs are
 * persistent (Citizens saves them) and tagged with the point they belong to, so {@link #reconcile}
 * can put things right after a restart: a missing NPC is created, a duplicate or an orphan removed.
 * Main thread only.
 */
public final class StallNpcService {

    public static final String KEY_STALL = "loveshops_stall_point";
    public static final String KEY_GUARD = "loveshops_guard_point";

    private final LoveShops plugin;

    public StallNpcService(LoveShops plugin) {
        this.plugin = plugin;
    }

    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    private static String legacy(String text) {
        return text == null ? "" : text;
    }

    /** @return the Citizens id, or {@code null} if it could not be created */
    public Integer createStallNpc(Location loc, UUID pointId, String ownerName) {
        if (!available() || loc == null || loc.getWorld() == null) return null;
        try {
            String rawFormat = legacy(plugin.getMarketConfig().npcNameFormat()).replace("{owner}", ownerName == null ? "?" : ownerName);
            String[] lines = rawFormat.split("\\r?\\n");
            String entityName = lines[lines.length - 1];
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, ChatColor.translateAlternateColorCodes('&', entityName));
            npc.data().setPersistent(KEY_STALL, pointId.toString());
            if (ownerName != null && !ownerName.isBlank()) {
                npc.getOrAddTrait(SkinTrait.class).setSkinName(ownerName);
            }
            if (plugin.getMarketConfig().npcLookClose()) {
                npc.getOrAddTrait(LookClose.class).lookClose(true);
            }
            npc.spawn(loc);
            TradePoint p = plugin.getTradePointManager() == null ? null : plugin.getTradePointManager().getPoint(pointId);
            if (p != null) {
                applyNpcHologram(npc, p);
            } else {
                HologramTrait holo = npc.getOrAddTrait(HologramTrait.class);
                holo.clear();
                for (int i = 0; i < lines.length - 1; i++) {
                    if (!lines[i].isBlank()) {
                        holo.addLine(ChatColor.translateAlternateColorCodes('&', lines[i]));
                    }
                }
            }
            return npc.getId();
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать NPC торговца для точки " + pointId + ": " + t.getMessage());
            return null;
        }
    }

    public void updateStallNpc(TradePoint point) {
        if (!available() || point == null || point.npcCitizensId() == null) return;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(point.npcCitizensId());
            if (npc != null) {
                applyNpcHologram(npc, point);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось обновить NPC для точки " + point.claimId() + ": " + t.getMessage());
        }
    }

    public void applyNpcHologram(NPC npc, TradePoint point) {
        if (npc == null || point == null) return;
        String ownerName = point.ownerName() == null ? "?" : point.ownerName();
        String rawFormat = legacy(plugin.getMarketConfig().npcNameFormat()).replace("{owner}", ownerName);
        String[] lines = rawFormat.split("\\r?\\n");
        String entityName = lines[lines.length - 1];
        npc.setName(ChatColor.translateAlternateColorCodes('&', entityName));

        HologramTrait holo = npc.getOrAddTrait(HologramTrait.class);
        holo.clear();

        String status = null;
        if (!point.open()) {
            if (point.closeReason() == CloseReason.ROBBERY) {
                status = plugin.getMarketConfig().statusRobbed();
            } else {
                status = plugin.getMarketConfig().statusClosed();
            }
        } else {
            if (point.tradingMode() == TradingMode.SELL_ONLY) {
                status = plugin.getMarketConfig().statusSellOnly();
            } else if (point.tradingMode() == TradingMode.BUY_ONLY) {
                status = plugin.getMarketConfig().statusBuyOnly();
            } else {
                status = plugin.getMarketConfig().statusOpen();
            }
        }

        if (status != null && !status.isBlank()) {
            holo.addLine(ChatColor.translateAlternateColorCodes('&', status));
        }

        for (int i = 0; i < lines.length - 1; i++) {
            if (!lines[i].isBlank()) {
                holo.addLine(ChatColor.translateAlternateColorCodes('&', lines[i]));
            }
        }
    }

    public void updateClosedSign(TradePoint point) {
        if (point == null) return;
        Location loc = point.closedSignLocation();
        if (loc == null || loc.getWorld() == null) return;
        org.bukkit.block.Block block = loc.getBlock();
        if (!point.open()) {
            if (!(block.getState() instanceof org.bukkit.block.Sign)) {
                block.setType(org.bukkit.Material.OAK_SIGN);
            }
            if (block.getState() instanceof org.bukkit.block.Sign sign) {
                List<String> lines = plugin.getMarketConfig().closedSignLines();
                String owner = point.ownerName() == null ? "" : point.ownerName();
                for (int i = 0; i < 4; i++) {
                    String line = i < lines.size() ? lines.get(i).replace("{owner}", owner) : "";
                    sign.line(i, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(line));
                }
                sign.update(true);
            }
        } else {
            if (plugin.getMarketConfig().closedSignRemoveOnOpen()) {
                if (block.getState() instanceof org.bukkit.block.Sign) {
                    block.setType(org.bukkit.Material.AIR);
                }
            }
        }
    }

    public Integer createGuardNpc(Location loc, UUID pointId) {
        if (!available() || loc == null || loc.getWorld() == null) return null;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, legacy(plugin.getMarketConfig().guardName()));
            npc.data().setPersistent(KEY_GUARD, pointId.toString());
            String skin = plugin.getMarketConfig().guardSkin();
            if (skin != null && !skin.isBlank()) {
                npc.getOrAddTrait(SkinTrait.class).setSkinName(skin);
            }
            if (plugin.getMarketConfig().npcLookClose()) {
                npc.getOrAddTrait(LookClose.class).lookClose(true);
            }
            npc.spawn(loc);
            return npc.getId();
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать NPC стражи для точки " + pointId + ": " + t.getMessage());
            return null;
        }
    }

    public void destroy(Integer citizensId) {
        if (citizensId == null || !available()) return;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getById(citizensId);
            if (npc != null) npc.destroy();
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось удалить NPC #" + citizensId + ": " + t.getMessage());
        }
    }

    /** {@code true} if the entity is the body of a market NPC (trader or guard). */
    public boolean isMarketEntity(org.bukkit.entity.Entity entity) {
        if (entity == null || !available()) return false;
        try {
            NPC npc = CitizensAPI.getNPCRegistry().getNPC(entity);
            return npc != null && (stallPointOf(npc).isPresent() || guardPointOf(npc).isPresent());
        } catch (Throwable t) {
            return false;
        }
    }

    public Optional<UUID> stallPointOf(NPC npc) {
        return pointOf(npc, KEY_STALL);
    }

    public Optional<UUID> guardPointOf(NPC npc) {
        return pointOf(npc, KEY_GUARD);
    }

    private Optional<UUID> pointOf(NPC npc, String key) {
        if (npc == null) return Optional.empty();
        String raw = npc.data().get(key, null);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Brings the world in line with the database: every point with a tenant has exactly one trader
     * NPC (and one guard when it pays for one); every other tagged NPC is removed.
     *
     * @param wantGuard whether a point should have a guard right now
     * @return the points whose stored NPC ids changed and need saving
     */
    public List<TradePoint> reconcile(java.util.Collection<TradePoint> points,
                                      Function<UUID, Location> locationOf,
                                      Function<TradePoint, Boolean> wantGuard) {
        List<TradePoint> changed = new ArrayList<>();
        if (!available()) return changed;
        NPCRegistry registry = CitizensAPI.getNPCRegistry();

        Map<UUID, List<NPC>> stalls = new HashMap<>();
        Map<UUID, List<NPC>> guards = new HashMap<>();
        for (NPC npc : registry) {
            stallPointOf(npc).ifPresent(id -> stalls.computeIfAbsent(id, k -> new ArrayList<>()).add(npc));
            guardPointOf(npc).ifPresent(id -> guards.computeIfAbsent(id, k -> new ArrayList<>()).add(npc));
        }

        List<NPC> toDestroy = new ArrayList<>();
        Map<UUID, TradePoint> byId = new HashMap<>();
        for (TradePoint p : points) byId.put(p.claimId(), p);

        // Tagged NPCs whose point is unknown or has no tenant: orphans.
        for (Map.Entry<UUID, List<NPC>> e : stalls.entrySet()) {
            TradePoint p = byId.get(e.getKey());
            if (p == null || !p.hasOwner()) toDestroy.addAll(e.getValue());
        }
        for (Map.Entry<UUID, List<NPC>> e : guards.entrySet()) {
            TradePoint p = byId.get(e.getKey());
            if (p == null || !p.hasOwner() || !Boolean.TRUE.equals(wantGuard.apply(p))) toDestroy.addAll(e.getValue());
        }

        for (TradePoint p : points) {
            updateClosedSign(p);

            if (!p.hasOwner()) {
                if (p.npcCitizensId() != null || p.guardCitizensId() != null) {
                    p.npcCitizensId(null);
                    p.guardCitizensId(null);
                    changed.add(p);
                }
                continue;
            }

            if (!p.open()) {
                for (NPC npc : stalls.getOrDefault(p.claimId(), List.of())) {
                    if (npc.isSpawned()) npc.despawn();
                }
                for (NPC npc : guards.getOrDefault(p.claimId(), List.of())) {
                    if (npc.isSpawned()) npc.despawn();
                }
                continue;
            }

            Location loc = locationOf.apply(p.claimId());
            if (loc == null) continue;

            List<NPC> mine = stalls.getOrDefault(p.claimId(), List.of());
            Integer wanted = mine.isEmpty() ? null : mine.get(0).getId();
            for (int i = 1; i < mine.size(); i++) toDestroy.add(mine.get(i));
            if (wanted == null) {
                wanted = createStallNpc(loc, p.claimId(), p.ownerName());
            } else {
                NPC npc = registry.getById(wanted);
                if (npc != null) {
                    if (!npc.isSpawned()) npc.spawn(loc);
                    applyNpcHologram(npc, p);
                }
            }
            if (!java.util.Objects.equals(wanted, p.npcCitizensId())) {
                p.npcCitizensId(wanted);
                changed.add(p);
            }

            if (Boolean.TRUE.equals(wantGuard.apply(p))) {
                List<NPC> myGuards = guards.getOrDefault(p.claimId(), List.of());
                Integer guardId = myGuards.isEmpty() ? null : myGuards.get(0).getId();
                for (int i = 1; i < myGuards.size(); i++) toDestroy.add(myGuards.get(i));
                if (guardId == null) guardId = createGuardNpc(loc.clone().add(1.5, 0, 0), p.claimId());
                else {
                    NPC gNpc = registry.getById(guardId);
                    if (gNpc != null && !gNpc.isSpawned()) gNpc.spawn(loc.clone().add(1.5, 0, 0));
                }
                if (!java.util.Objects.equals(guardId, p.guardCitizensId())) {
                    p.guardCitizensId(guardId);
                    if (!changed.contains(p)) changed.add(p);
                }
            } else if (p.guardCitizensId() != null) {
                p.guardCitizensId(null);
                if (!changed.contains(p)) changed.add(p);
            }
        }

        for (NPC npc : toDestroy) {
            try {
                npc.destroy();
            } catch (Throwable t) {
                plugin.getLogger().warning("Не удалось удалить лишний NPC #" + npc.getId() + ": " + t.getMessage());
            }
        }
        return changed;
    }
}
