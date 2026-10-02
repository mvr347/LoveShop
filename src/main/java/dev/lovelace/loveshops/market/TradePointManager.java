package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.gui.MarketGui;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.PlayerClass;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle and owner-side operations of trade points: rent events from LoveClaims, the trader NPC,
 * the till, the shelves. Everything runs on the main thread (clicks, Bukkit events and main-thread
 * tasks), so the in-memory {@link TradePoint}s need no locking; the database is the source of truth
 * for goods and money, written before anything is handed to a player.
 */
public final class TradePointManager {

    public enum ListingResult {
        OK, NOT_OWNER, NO_ITEM, IS_COIN, FORBIDDEN, PRICE_LOW, PRICE_HIGH, NO_SLOT, SLOT_TAKEN,
        LISTING_GONE, DB_ERROR, NO_SPACE
    }

    public enum OpenResult { OPENED, CLOSED, DENIED_REASON, NOT_OWNER }

    public record TillResult(boolean ok, String reason, long amount) {}

    private final LoveShops plugin;
    private final MarketRepository repo;
    private final StallNpcService npcs;
    private final ClaimsLink claims;
    private final ReputationGate gate;
    private final TaxService tax;
    private final ReturnsService returns;

    private final Map<UUID, TradePoint> points = new HashMap<>();
    /** Open market GUIs by viewer, so a change of a point can refresh or close what its viewers see. */
    private final Map<UUID, MarketGui> viewers = new HashMap<>();
    private BukkitTask upkeepTask;
    private int pruneTicks;
    private RobberyService robbery;
    private GuardService guards;
    /** Last NPC click per player: Citizens can report one click twice (both hands) and menus must not flicker open. */
    private final Map<UUID, Long> lastNpcClick = new HashMap<>();

    public TradePointManager(LoveShops plugin, MarketRepository repo, StallNpcService npcs, ClaimsLink claims,
                             ReputationGate gate, TaxService tax, ReturnsService returns) {
        this.plugin = plugin;
        this.repo = repo;
        this.npcs = npcs;
        this.claims = claims;
        this.gate = gate;
        this.tax = tax;
        this.returns = returns;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Wires the services that need this manager (they are built after it). */
    public void attach(RobberyService robbery, GuardService guards) {
        this.robbery = robbery;
        this.guards = guards;
    }

    public void enable() {
        try {
            for (TradePoint p : repo.loadPoints()) points.put(p.claimId(), p);
        } catch (SQLException e) {
            plugin.getLogger().severe("Не удалось загрузить торговые точки: " + e.getMessage());
        }
        claims.register(this);
        // Citizens loads its NPCs in its own onEnable; give the world a moment before reconciling.
        Bukkit.getScheduler().runTaskLater(plugin, this::syncWithClaims, 60L);
        upkeepTask = Bukkit.getScheduler().runTaskTimer(plugin, this::upkeep, 600L, 600L);
    }

    public void disable() {
        if (upkeepTask != null) {
            upkeepTask.cancel();
            upkeepTask = null;
        }
        closeAllViewers();
        claims.unregister();
        points.clear();
        lastNpcClick.clear();
    }

    /** Puts the shop data in line with LoveClaims after a start (rents may have changed while we were off). */
    private void syncWithClaims() {
        Map<UUID, ClaimsLink.PointInfo> infos = new HashMap<>();
        for (ClaimsLink.PointInfo info : claims.tradePoints()) infos.put(info.claimId(), info);

        for (ClaimsLink.PointInfo info : infos.values()) {
            TradePoint p = points.get(info.claimId());
            if (p == null) {
                p = new TradePoint(info.claimId());
                points.put(info.claimId(), p);
                save(p);
            }
            if (p.hasOwner() && !p.ownerUuid().equals(info.tenant())) {
                // The tenant changed while LoveShops was off: settle the old one first.
                releaseInternal(p, "OFFLINE_CHANGE");
            }
            if (!p.hasOwner() && info.tenant() != null) {
                rentInternal(p, info.tenant());
            }
            if (p.hasOwner() && info.tenant() == null) {
                // Claim gone or tenant cleared offline.
                if (p.hasOwner()) releaseInternal(p, "CLAIM_REMOVED");
            }
            if (info.home() != null) {
                p.home(info.home());
            }
        }
        // Points in DB that no longer exist in Claims.
        for (UUID id : new ArrayList<>(points.keySet())) {
            if (!infos.containsKey(id)) {
                TradePoint p = points.get(id);
                if (p != null && p.hasOwner()) releaseInternal(p, "CLAIM_GONE");
                points.remove(id);
                try { repo.deletePoint(id); } catch (SQLException ignored) {}
            }
        }
        reconcileNpcs();
    }

    public void reconcileNpcs() {
        for (TradePoint p : points.values()) {
            if (p.hasOwner()) {
                npcs.ensureTrader(p);
                if (p.guardState() != GuardState.NONE) npcs.ensureGuard(p);
            }
        }
        npcs.reconcile();
    }

    private void upkeep() {
        pruneTicks++;
        if (pruneTicks % 12 == 0) {
            // every ~2 min
            try { repo.pruneStale(); } catch (SQLException e) {
                plugin.getLogger().warning("prune: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ claim events

    public void onRented(UUID claimId, UUID tenant) {
        TradePoint p = points.computeIfAbsent(claimId, TradePoint::new);
        if (p.hasOwner() && !p.ownerUuid().equals(tenant)) {
            releaseInternal(p, "REPLACED");
        }
        rentInternal(p, tenant);
    }

    public void onReleased(UUID claimId, String reason) {
        TradePoint p = points.get(claimId);
        if (p != null && p.hasOwner()) {
            releaseInternal(p, reason);
        }
    }

    /**
     * Claim fully deleted in LoveClaims. Clean market row + NPCs even without a tenant.
     */
    public void onClaimDeleted(UUID claimId) {
        TradePoint p = points.get(claimId);
        if (p != null) {
            if (p.hasOwner()) {
                releaseInternal(p, "CLAIM_DELETED");
            } else {
                closeViewers(claimId);
                npcs.destroyAllForPoint(claimId);
            }
            try {
                repo.deletePoint(claimId);
            } catch (SQLException e) {
                plugin.getLogger().warning("Не удалось удалить строку точки " + claimId + ": " + e.getMessage());
            }
            points.remove(claimId);
        } else {
            npcs.destroyAllForPoint(claimId);
        }
    }

    private void rentInternal(TradePoint p, UUID tenant) {
        p.ownerUuid(tenant);
        p.rentedAt(System.currentTimeMillis());
        p.open(true);
        save(p);
        npcs.ensureTrader(p);
        plugin.getLogger().info("Trade point " + p.claimId() + " rented by " + tenant);
    }

    private void releaseInternal(TradePoint p, String reason) {
        closeViewers(p.claimId());
        // Return goods + till to pending_returns for the owner
        try {
            returns.enqueueRelease(p);
        } catch (Exception e) {
            plugin.getLogger().warning("release enqueue " + p.claimId() + ": " + e.getMessage());
        }
        NPC npc = npcs.findTrader(p.claimId());
        NPC guard = npcs.findGuard(p.claimId());
        TradePoint reset = p.resetForRelease();
        p.ownerUuid(null);
        p.open(false);
        p.listings(List.of());
        p.till(0);
        p.guardState(GuardState.NONE);
        p.guardPaidUntil(0);
        p.rentedAt(0);
        p.version(reset.version());
        // Tag-scan: remove every Citizens NPC bound to this point (orphans too).
        npcs.destroyAllForPoint(p.claimId());
        if (npc != null) npcs.destroy(npc);
        if (guard != null) npcs.destroy(guard);
        save(p);
        plugin.getLogger().info("Trade point " + p.claimId() + " released: " + reason);
    }

    // ------------------------------------------------------------------ viewers

    public void registerViewer(Player player, MarketGui gui) {
        viewers.put(player.getUniqueId(), gui);
    }

    public void unregisterViewer(Player player) {
        viewers.remove(player.getUniqueId());
    }

    public void closeViewers(UUID claimId) {
        for (Map.Entry<UUID, MarketGui> e : new ArrayList<>(viewers.entrySet())) {
            MarketGui g = e.getValue();
            if (g != null && claimId.equals(g.pointId())) {
                Player pl = Bukkit.getPlayer(e.getKey());
                if (pl != null) pl.closeInventory();
                viewers.remove(e.getKey());
            }
        }
    }

    private void closeAllViewers() {
        for (UUID id : new ArrayList<>(viewers.keySet())) {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) pl.closeInventory();
        }
        viewers.clear();
    }

    public void refreshViewers(UUID claimId) {
        for (Map.Entry<UUID, MarketGui> e : viewers.entrySet()) {
            MarketGui g = e.getValue();
            if (g != null && claimId.equals(g.pointId())) {
                g.refresh();
            }
        }
    }

    // ------------------------------------------------------------------ accessors

    public Optional<TradePoint> get(UUID claimId) {
        return Optional.ofNullable(points.get(claimId));
    }

    public Collection<TradePoint> all() {
        return points.values();
    }

    public TradePoint require(UUID claimId) {
        TradePoint p = points.get(claimId);
        if (p == null) throw new IllegalStateException("No trade point " + claimId);
        return p;
    }

    public MarketRepository repo() { return repo; }
    public StallNpcService npcs() { return npcs; }
    public ClaimsLink claims() { return claims; }
    public ReputationGate gate() { return gate; }
    public TaxService tax() { return tax; }
    public ReturnsService returns() { return returns; }
    public RobberyService robbery() { return robbery; }
    public GuardService guards() { return guards; }
    public LoveShops plugin() { return plugin; }

    public boolean save(TradePoint p) {
        try {
            repo.savePoint(p);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("Точка " + p.claimId() + " не сохранена: " + e.getMessage());
            return false;
        }
    }
}
