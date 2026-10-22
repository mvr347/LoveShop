package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.npc.NpcHandle;
import dev.lovelace.lovecore.api.npc.NpcSpec;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import dev.lovelace.loveshops.npc.NpcLookup;
import dev.lovelace.loveshops.npc.NpcTags;
import dev.lovelace.loveshops.npc.NpcText;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Creates, finds and removes the NPCs of trade points (the trader and the guard) through the configured NPC provider.
 */
public final class StallNpcService {

    public static final String KEY_STALL = NpcTags.STALL;
    public static final String KEY_GUARD = NpcTags.GUARD;

    private final LoveShops plugin;

    public StallNpcService(LoveShops plugin) {
        this.plugin = plugin;
    }

    /** Whether the configured NPC provider is registered right now. */
    public boolean available() {
        return plugin.getNpcBridge().available();
    }

    private static String legacy(String text) {
        return text == null ? "" : text;
    }

    /** @return the key of the new trader NPC, or {@code null} when it could not be created */
    public String createStallNpc(Location loc, UUID pointId, String ownerName) {
        if (loc == null || loc.getWorld() == null || !available()) return null;
        try {
            // Убираем возможных дублей перед созданием
            destroyAllForPoint(pointId);

            NpcSpec spec = NpcSpec.at(NpcText.amp(plugin.getMarketConfig().statusClosed()), loc)
                    .withTags(Map.of(KEY_STALL, pointId.toString()))
                    .withLookClose(plugin.getMarketConfig().npcLookClose());
            if (ownerName != null && !ownerName.isBlank()) spec = spec.withSkinName(ownerName);
            Optional<NpcHandle> created = plugin.getNpcBridge().create(spec);
            if (created.isEmpty()) return null;
            NpcHandle npc = created.get();
            TradePoint p = plugin.getTradePointManager() == null ? null : plugin.getTradePointManager().getPoint(pointId);
            if (p != null) applyNpcHologram(npc, p);
            return npc.key();
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать NPC торговца для точки " + pointId + ": " + t.getMessage());
            return null;
        }
    }

    public void updateStallNpc(TradePoint point) {
        if (point == null || point.npcKey() == null || !available()) return;
        try {
            plugin.getNpcBridge().byKey(point.npcKey()).ifPresent(npc -> applyNpcHologram(npc, point));
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось обновить NPC для точки " + point.claimId() + ": " + t.getMessage());
        }
    }

    /** Where the point's trader stands right now; empty when it is not spawned or the provider is gone. */
    public Optional<Location> traderLocation(TradePoint point) {
        if (point == null || point.npcKey() == null) return Optional.empty();
        try {
            return plugin.getNpcBridge().byKey(point.npcKey())
                    .filter(NpcHandle::spawned)
                    .flatMap(NpcHandle::entity)
                    .map(org.bukkit.entity.Entity::getLocation);
        } catch (Throwable ignored) {
            // intentional: the provider may be unloading; the caller falls back to the claim point
            return Optional.empty();
        }
    }

    /** Moves the point's trader to {@code to}; {@code false} when there is no spawned trader to move. */
    public boolean teleportTrader(TradePoint point, Location to) {
        if (point == null || point.npcKey() == null || to == null) return false;
        try {
            Optional<NpcHandle> npc = plugin.getNpcBridge().byKey(point.npcKey());
            if (npc.isEmpty() || !npc.get().spawned()) return false;
            npc.get().teleport(to);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось телепортировать NPC: " + t.getMessage());
            return false;
        }
    }

    /**
     * Text above the trader, top to bottom: the format lines ("Trade point No. {id}", "Owner: {owner}")
     * as hologram lines and the status ("No lots", "Open", ...) as the nameplate right above the head.
     */
    public void applyNpcHologram(NpcHandle npc, TradePoint point) {
        if (npc == null || point == null) return;
        var manager = plugin.getTradePointManager();
        String ownerName = point.ownerName() == null ? "?" : point.ownerName();
        String id = manager == null ? "?" : manager.nameOf(point);
        npc.setName(NpcText.amp(statusText(point)));

        String format = legacy(plugin.getMarketConfig().npcNameFormat()).replace("{owner}", ownerName).replace("{id}", id);
        npc.hologram(NpcText.ampLines(format));
    }

    /** The status line of a point, with its kind decided by {@link NpcStatus}. */
    public String statusText(TradePoint point) {
        var cfg = plugin.getMarketConfig();
        boolean hasSell = false;
        boolean hasBuy = false;
        if (point.open() && plugin.getTradePointManager() != null) {
            for (var l : plugin.getTradePointManager().listings(point)) {
                if (l.type() == dev.lovelace.loveshops.market.model.ListingType.SELL) hasSell |= l.stock() > 0;
                else hasBuy |= l.stock() < l.maxAmount();
            }
        }
        return switch (NpcStatus.pick(point.open(), point.closeReason() == CloseReason.ROBBERY,
                point.tradingMode(), hasSell, hasBuy)) {
            case ROBBED -> cfg.statusRobbed();
            case CLOSED -> cfg.statusClosed();
            case EMPTY -> cfg.statusEmpty();
            case SELL_ONLY -> cfg.statusSellOnly();
            case BUY_ONLY -> cfg.statusBuyOnly();
            case OPEN -> cfg.statusOpen();
        };
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

    /**
     * The sign with the point's id (set by the creation wizard): id, and whether the point is free or
     * who rents it. Rewritten on every reconcile, so it follows rents and releases by itself.
     */
    public void updateIdSign(TradePoint point, String id, String status) {
        if (point == null) return;
        Location loc = point.idSignLocation();
        if (loc == null || loc.getWorld() == null) return;
        org.bukkit.block.Block block = loc.getBlock();
        if (!(block.getState() instanceof org.bukkit.block.Sign sign)) return;
        List<String> lines = plugin.getMarketConfig().idSignLines();
        for (int i = 0; i < 4; i++) {
            String line = i < lines.size() ? lines.get(i).replace("{id}", id == null ? "?" : id)
                    .replace("{status}", status == null ? "" : status) : "";
            sign.line(i, net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
        sign.update(true);
    }

    /** The point is gone for good: take its "closed" sign down too, so no orphan sign is left in the world. */
    public void removeClosedSign(TradePoint point) {
        if (point == null) return;
        removeSignAt(point.closedSignLocation());
        removeSignAt(point.idSignLocation());
    }

    private static void removeSignAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        org.bukkit.block.Block block = loc.getBlock();
        if (block.getState() instanceof org.bukkit.block.Sign) block.setType(org.bukkit.Material.AIR);
    }

    public String createGuardNpc(Location loc, UUID pointId) {
        if (loc == null || loc.getWorld() == null || !available()) return null;
        try {
            NpcSpec spec = NpcSpec.at(NpcText.section(legacy(plugin.getMarketConfig().guardName())), loc)
                    .withTags(Map.of(KEY_GUARD, pointId.toString()))
                    .withLookClose(plugin.getMarketConfig().npcLookClose());
            String skin = plugin.getMarketConfig().guardSkin();
            if (skin != null && !skin.isBlank()) spec = spec.withSkinName(skin);
            return plugin.getNpcBridge().create(spec).map(NpcHandle::key).orElse(null);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось создать NPC стражи для точки " + pointId + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * Hard removal of the NPC a point row names (a key written by an earlier run). An NPC that has since been
     * claimed by something else - another point, a LoveShops role or the landlord - is left alone: provider
     * ids can be reused after a deletion, and the row must not take a stranger down with it.
     */
    public void destroy(String key, UUID pointId) {
        if (key == null || !available()) return;
        try {
            Optional<NpcHandle> npc = plugin.getNpcBridge().byKey(key);
            if (npc.isPresent() && !NpcLookup.claimedByOther(npc.get(), pointId)) {
                plugin.getNpcBridge().remove(npc.get().key());
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось удалить NPC #" + key + ": " + t.getMessage());
        }
    }

    /**
     * Удаляет ВСЕ NPC (торговец + стража), помеченные точкой — даже если ключ в БД устарел.
     * Решает проблему «NPC не удаляется после удаления плота».
     */
    public void destroyAllForPoint(UUID pointId) {
        if (pointId == null || !available()) return;
        String id = pointId.toString();
        try {
            Map<String, NpcHandle> victims = new HashMap<>();
            for (NpcHandle npc : plugin.getNpcBridge().withTag(KEY_STALL, id)) victims.put(npc.key(), npc);
            for (NpcHandle npc : plugin.getNpcBridge().withTag(KEY_GUARD, id)) victims.put(npc.key(), npc);
            for (NpcHandle npc : victims.values()) {
                hardDestroy(npc);
            }
            if (!victims.isEmpty()) {
                plugin.getLogger().info("Удалено NPC торговой точки " + pointId + ": " + victims.size());
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("destroyAllForPoint(" + pointId + "): " + t.getMessage());
        }
    }

    private void hardDestroy(NpcHandle npc) {
        try {
            plugin.getNpcBridge().remove(npc.key());
        } catch (Throwable t) {
            plugin.getLogger().warning("remove NPC #" + npc.key() + ": " + t.getMessage());
        }
    }

    public boolean isMarketEntity(org.bukkit.entity.Entity entity) {
        if (entity == null || !available()) return false;
        try {
            return plugin.getNpcBridge().byEntity(entity)
                    .filter(npc -> stallPointOf(npc).isPresent() || guardPointOf(npc).isPresent())
                    .isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    public Optional<UUID> stallPointOf(NpcHandle npc) {
        return pointOf(npc, KEY_STALL);
    }

    public Optional<UUID> guardPointOf(NpcHandle npc) {
        return pointOf(npc, KEY_GUARD);
    }

    private Optional<UUID> pointOf(NpcHandle npc, String key) {
        if (npc == null) return Optional.empty();
        String raw = npc.tag(key).orElse(null);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * One-time fallback for NPCs made before tags moved into the provider's own storage (Citizens): such an NPC
     * carries none of our tags, but the point row still names it. It is tagged from the row so the tag scan below
     * finds it. Only the Citizens provider ever stored the old tags, and only an NPC with no LoveShops identity
     * at all is adopted - a stale id that now points at someone else's NPC is left alone.
     */
    private void adoptLegacy(java.util.Collection<TradePoint> points, Map<UUID, List<NpcHandle>> stalls,
                             Map<UUID, List<NpcHandle>> guards) {
        if (!plugin.getNpcBridge().ownsProvider("citizens")) return;
        int adopted = 0;
        for (TradePoint p : points) {
            if (!p.hasOwner()) continue;
            adopted += adoptOne(p.npcKey(), KEY_STALL, p.claimId(), stalls);
            adopted += adoptOne(p.guardKey(), KEY_GUARD, p.claimId(), guards);
        }
        if (adopted > 0) {
            plugin.getLogger().info("Рынок: повторно помечено NPC торговых точек по записям БД (метки старого формата): " + adopted);
        }
    }

    private int adoptOne(String key, String tag, UUID pointId, Map<UUID, List<NpcHandle>> byPoint) {
        if (key == null || !byPoint.getOrDefault(pointId, List.of()).isEmpty()) return 0;
        Optional<NpcHandle> npc = plugin.getNpcBridge().byKey(key);
        if (npc.isEmpty()) return 0;
        NpcHandle handle = npc.get();
        if (NpcLookup.carriesIdentity(handle)) return 0;
        handle.tag(tag, pointId.toString());
        byPoint.computeIfAbsent(pointId, k -> new ArrayList<>()).add(handle);
        return 1;
    }

    /**
     * Shows an NPC of an open point that the provider keeps hidden (the previous build despawned the NPCs of closed
     * points). Only in a loaded chunk: spawning into an unloaded one would load it for a trader nobody is near.
     */
    private static void showIfHidden(NpcHandle npc, Location where) {
        if (npc.spawned() || where == null || where.getWorld() == null) return;
        if (where.getWorld().isChunkLoaded(where.getBlockX() >> 4, where.getBlockZ() >> 4)) npc.spawn();
    }

    public List<TradePoint> reconcile(java.util.Collection<TradePoint> points,
                                      Function<UUID, Location> locationOf,
                                      Function<TradePoint, Boolean> wantGuard) {
        List<TradePoint> changed = new ArrayList<>();
        if (!available()) return changed;

        Map<UUID, List<NpcHandle>> stalls = new HashMap<>();
        Map<UUID, List<NpcHandle>> guards = new HashMap<>();
        for (NpcHandle npc : plugin.getNpcBridge().all()) {
            stallPointOf(npc).ifPresent(id -> stalls.computeIfAbsent(id, k -> new ArrayList<>()).add(npc));
            guardPointOf(npc).ifPresent(id -> guards.computeIfAbsent(id, k -> new ArrayList<>()).add(npc));
        }
        adoptLegacy(points, stalls, guards);

        List<NpcHandle> toDestroy = new ArrayList<>();
        Map<UUID, TradePoint> byId = new HashMap<>();
        for (TradePoint p : points) byId.put(p.claimId(), p);

        for (Map.Entry<UUID, List<NpcHandle>> e : stalls.entrySet()) {
            TradePoint p = byId.get(e.getKey());
            if (p == null || !p.hasOwner()) toDestroy.addAll(e.getValue());
        }
        for (Map.Entry<UUID, List<NpcHandle>> e : guards.entrySet()) {
            TradePoint p = byId.get(e.getKey());
            if (p == null || !p.hasOwner() || !Boolean.TRUE.equals(wantGuard.apply(p))) toDestroy.addAll(e.getValue());
        }

        for (TradePoint p : points) {
            updateClosedSign(p);

            if (!p.hasOwner()) {
                if (p.npcKey() != null || p.guardKey() != null) {
                    p.npcKey(null);
                    p.guardKey(null);
                    changed.add(p);
                }
                continue;
            }

            // A closed point is left as it is: nothing is created for it, and an existing trader stays on show with
            // its "closed" nameplate (closing a point while the server runs has never hidden the trader).
            if (!p.open()) continue;

            Location loc = locationOf.apply(p.claimId());
            if (loc == null) continue;

            List<NpcHandle> mine = stalls.getOrDefault(p.claimId(), List.of());
            String wanted = null;
            if (!mine.isEmpty()) {
                NpcHandle first = mine.get(0);
                for (int i = 1; i < mine.size(); i++) toDestroy.add(mine.get(i));
                showIfHidden(first, loc);
                wanted = first.key();
                applyNpcHologram(first, p);
            }
            if (wanted == null) {
                wanted = createStallNpc(loc, p.claimId(), p.ownerName());
            }
            if (!java.util.Objects.equals(wanted, p.npcKey())) {
                p.npcKey(wanted);
                changed.add(p);
            }

            if (Boolean.TRUE.equals(wantGuard.apply(p))) {
                List<NpcHandle> myGuards = guards.getOrDefault(p.claimId(), List.of());
                Location guardAt = loc.clone().add(1.5, 0, 0);
                String guardKey = null;
                if (!myGuards.isEmpty()) {
                    NpcHandle first = myGuards.get(0);
                    for (int i = 1; i < myGuards.size(); i++) toDestroy.add(myGuards.get(i));
                    showIfHidden(first, guardAt);
                    guardKey = first.key();
                }
                if (guardKey == null) guardKey = createGuardNpc(guardAt, p.claimId());
                if (!java.util.Objects.equals(guardKey, p.guardKey())) {
                    p.guardKey(guardKey);
                    if (!changed.contains(p)) changed.add(p);
                }
            } else if (p.guardKey() != null) {
                p.guardKey(null);
                if (!changed.contains(p)) changed.add(p);
            }
        }

        for (NpcHandle npc : toDestroy) {
            hardDestroy(npc);
        }
        return changed;
    }
}
