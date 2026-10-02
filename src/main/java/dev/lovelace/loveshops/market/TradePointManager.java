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

    /** Rent reminders: the threshold (hours) last reminded about, per point. In memory only. */
    private final Map<UUID, Integer> remindedHours = new HashMap<>();
    /** Overdue tenants: when the last "closed, will be confiscated" reminder went out. */
    private final Map<UUID, Long> graceRemindedAt = new HashMap<>();

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
        }
        // A point whose claim was deleted: give the goods back and forget it.
        for (TradePoint p : new ArrayList<>(points.values())) {
            if (!infos.containsKey(p.claimId())) {
                if (p.hasOwner()) releaseInternal(p, "CLAIM_REMOVED");
                try {
                    repo.deletePoint(p.claimId());
                } catch (SQLException e) {
                    plugin.getLogger().warning("Не удалось удалить строку точки " + p.claimId() + ": " + e.getMessage());
                }
                points.remove(p.claimId());
            }
        }
        reconcileNpcs();
        upkeep();
    }

    /** Re-creates/removes trader and guard NPCs to match the data (after a hire, a firing, a release). */
    public void syncNpcs() {
        reconcileNpcs();
    }

    public void reconcileNpcs() {
        List<TradePoint> changed = npcs.reconcile(points.values(),
                id -> claims.point(id).map(ClaimsLink.PointInfo::location).orElse(null),
                p -> p.guardState() == GuardState.ACTIVE);
        for (TradePoint p : changed) save(p);
        for (TradePoint p : points.values()) updateIdSign(p);
    }

    /** Rewrites the id sign of a point (id and "free" / tenant name). */
    public void updateIdSign(TradePoint p) {
        if (p.idSignLocation() == null) return;
        String id = nameOf(p);
        String status = p.hasOwner()
                ? "&c" + (p.ownerName() == null ? "?" : p.ownerName())
                : plugin.getMarketMessages().raw("id-sign-free");
        npcs.updateIdSign(p, id, status);
    }

    // ------------------------------------------------------------------ queries

    /** The id (plot name) of a point, as LoveClaims knows it. */
    public String nameOf(TradePoint p) {
        return claims.point(p.claimId()).map(ClaimsLink.PointInfo::name).orElse(p.claimId().toString().substring(0, 8));
    }

    /** A point by its id (the plot name, any case). */
    public Optional<TradePoint> byName(String id) {
        return claims.byName(id).map(info -> points.get(info.claimId()));
    }

    /** What the point is called in lists and messages, plus the weekly price from LoveClaims. */
    public Optional<ClaimsLink.PointInfo> infoOf(TradePoint p) {
        return claims.point(p.claimId());
    }

    /** Where {@code /tp <id>} takes a player: the teleport spot set by the wizard, else next to the trader. */
    public Location teleportTarget(TradePoint p) {
        if (p.teleportLocation() != null && p.teleportLocation().getWorld() != null) return p.teleportLocation();
        return getNpcOrPointLocation(p);
    }

    public Optional<TradePoint> byClaim(UUID claimId) {
        return Optional.ofNullable(points.get(claimId));
    }

    public TradePoint getPoint(UUID claimId) {
        return points.get(claimId);
    }

    public Optional<TradePoint> byOwner(UUID owner) {
        return points.values().stream().filter(p -> p.isOwner(owner)).findFirst();
    }

    public Collection<TradePoint> all() {
        return List.copyOf(points.values());
    }

    public Optional<TradePoint> pointAt(Location loc) {
        return claims.pointAt(loc).flatMap(this::byClaim);
    }

    public List<StallListing> listings(TradePoint p) {
        try {
            return repo.listings(p.claimId());
        } catch (SQLException e) {
            plugin.getLogger().warning("Не удалось прочитать лоты точки " + p.claimId() + ": " + e.getMessage());
            return List.of();
        }
    }

    public long rentEnd(TradePoint p) {
        return claims.point(p.claimId()).map(ClaimsLink.PointInfo::rentEnd).orElse(0L);
    }

    // ------------------------------------------------------------------ rent events

    /** Rent gate for LoveClaims' cancellable request: outcasts and aggressors stay off the market. */
    public Optional<String> rentDenial(Player player) {
        if (gate.canRent(player.getUniqueId())) return Optional.empty();
        return Optional.of(plugin.getMarketMessages().raw("rent-denied-reputation"));
    }

    public void onRented(UUID player, UUID claimId) {
        TradePoint p = points.get(claimId);
        if (p == null) {
            p = new TradePoint(claimId);
            points.put(claimId, p);
        }
        if (p.hasOwner() && !p.isOwner(player)) {
            releaseInternal(p, "REPLACED");
        }
        rentInternal(p, player);
        reconcileNpcs();
        Player online = Bukkit.getPlayer(player);
        if (online != null) plugin.getMarketMessages().send(online, "stall-rented");
    }

    private void rentInternal(TradePoint p, UUID player) {
        String name = Bukkit.getOfflinePlayer(player).getName();
        int level = 1;
        try {
            level = repo.traderLevel(player);
        } catch (SQLException e) {
            plugin.getLogger().warning("Уровень торговца " + player + " не прочитан: " + e.getMessage());
        }
        p.ownerUuid(player);
        p.ownerName(name);
        p.level(level);
        p.sellSlots(UpgradeMath.slots(plugin.getMarketConfig().baseSellSlots(), level));
        p.buySlots(UpgradeMath.slots(plugin.getMarketConfig().baseBuySlots(), level));
        p.open(true);
        p.closeReason(null);
        p.tillCoins(0);
        p.guardState(GuardState.NONE);
        p.guardPaidUntil(0);
        // A new tenant starts selling only; buy orders are switched on in the Management menu.
        p.tradingMode(dev.lovelace.loveshops.market.model.TradingMode.SELL_ONLY);
        p.rentedAt(System.currentTimeMillis());
        remindedHours.remove(p.claimId());
        graceRemindedAt.remove(p.claimId());
        save(p);
    }

    public boolean transfer(TradePoint p, UUID newOwner) {
        if (p == null || newOwner == null) return false;
        String name = Bukkit.getOfflinePlayer(newOwner).getName();
        int level = 1;
        try {
            level = repo.traderLevel(newOwner);
        } catch (SQLException e) {
            plugin.getLogger().warning("Уровень торговца " + newOwner + " не прочитан: " + e.getMessage());
        }
        p.ownerUuid(newOwner);
        p.ownerName(name);
        p.level(level);
        p.sellSlots(UpgradeMath.slots(plugin.getMarketConfig().baseSellSlots(), level));
        p.buySlots(UpgradeMath.slots(plugin.getMarketConfig().baseBuySlots(), level));
        claims.transferTenant(p.claimId(), newOwner);
        save(p);
        reconcileNpcs();
        refreshViewers(p.claimId());
        return true;
    }

    public void onReleased(UUID player, UUID claimId, String reason) {
        TradePoint p = points.get(claimId);
        if (p == null || !p.hasOwner()) return;
        UUID owner = p.ownerUuid();
        releaseInternal(p, reason);
        Player online = Bukkit.getPlayer(owner);
        String key = "stall-released";
        if (online != null) {
            plugin.getMarketMessages().send(online, key);
            claimReturns(online);
        } else {
            notice(owner, plugin.getMarketMessages().raw(key));
        }
    }

    public void onExpiryWarning(UUID player, UUID claimId, long millisLeft) {
        // The reminder schedule (remindRent) covers the 24 h warning too; this stays the fallback.
        if (plugin.getMarketConfig().rentRemindersEnabled()) return;
        TradePoint p = points.get(claimId);
        if (p == null || !p.isOwner(player)) return;
        String hours = String.valueOf(Math.max(1, millisLeft / 3_600_000L));
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            plugin.getMarketMessages().send(online, "rent-warning", "hours", hours);
        } else {
            notice(player, plugin.getMarketMessages().raw("rent-warning", "hours", hours));
        }
    }

    /**
     * The tenant is gone. Goods and till go to their returns in ONE transaction with the point reset,
     * so a crash leaves either the old state (repaired by the next start) or the finished one.
     */
    private void releaseInternal(TradePoint p, String reason) {
        UUID owner = p.ownerUuid();
        if (owner == null) return;
        closeViewers(p.claimId());
        remindedHours.remove(p.claimId());
        graceRemindedAt.remove(p.claimId());
        Integer npc = p.npcCitizensId();
        Integer guard = p.guardCitizensId();
        long till = p.tillCoins();

        // The reset is written from a scratch copy and applied to the live point only after the
        // commit: a failed transaction must leave memory and database both on the old tenant.
        TradePoint reset = new TradePoint(p.claimId());
        reset.version(p.version());
        try {
            repo.inTransaction(conn -> {
                for (StallListing l : repo.listings(conn, p.claimId())) {
                    repo.addReturnItem(conn, owner, l.template(), l.stock(), "stall-released:" + reason);
                }
                repo.addReturnCoins(conn, owner, till, "stall-released:" + reason);
                repo.deleteListings(conn, p.claimId());
                repo.savePoint(conn, reset);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().severe("Точка " + p.claimId() + " не освобождена (" + reason + "): " + e.getMessage()
                    + " - будет повторено при следующем запуске.");
            return;
        }
        p.ownerUuid(null);
        p.ownerName(null);
        p.npcCitizensId(null);
        p.guardCitizensId(null);
        p.level(1);
        p.open(false);
        p.closeReason(null);
        p.tillCoins(0);
        p.revenueTotal(0);
        p.salesTotal(0);
        p.guardState(GuardState.NONE);
        p.guardPaidUntil(0);
        p.rentedAt(0);
        p.version(reset.version());
        // Tag-scan: remove every Citizens NPC bound to this point (orphans too).
        npcs.destroyAllForPoint(p.claimId());
        if (npc != null) npcs.destroy(npc);
        if (guard != null) npcs.destroy(guard);
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
            npcs.removeClosedSign(p);
            points.remove(claimId);
            remindedHours.remove(claimId);
            graceRemindedAt.remove(claimId);
        } else {
            npcs.destroyAllForPoint(claimId);
        }
    }

    // ------------------------------------------------------------------ admin operations (/tradepointadmin)

    /** Sets the point's level (1..max) together with its shelf counts; the tenant's trader level follows. */
    public boolean adminSetLevel(TradePoint p, int level) {
        int clamped = Math.max(1, Math.min(plugin.getMarketConfig().maxLevel(), level));
        int sell = UpgradeMath.slots(plugin.getMarketConfig().baseSellSlots(), clamped);
        int buy = UpgradeMath.slots(plugin.getMarketConfig().baseBuySlots(), clamped);
        try {
            repo.inTransaction(conn -> {
                repo.setPointLevel(conn, p.claimId(), clamped, sell, buy);
                if (p.hasOwner()) repo.setTraderLevel(conn, p.ownerUuid(), clamped);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().warning("Уровень точки " + p.claimId() + " не записан: " + e.getMessage());
            return false;
        }
        p.level(clamped);
        p.sellSlots(sell);
        p.buySlots(buy);
        refreshViewers(p.claimId());
        return true;
    }

    /** Gives the point to {@code tenant} for one rental period without payment (admin). */
    public void adminAssign(TradePoint p, UUID tenant) {
        claims.assign(p.claimId(), tenant, System.currentTimeMillis() + claims.periodMillis());
    }

    /** Takes the point from its tenant (admin); goods and till go to their returns. */
    public void adminRelease(TradePoint p) {
        claims.release(p.claimId(), "ADMIN");
    }

    /** A trade point that was just created in LoveClaims: gets its (free) market row. */
    public TradePoint registerNewPoint(UUID claimId) {
        TradePoint p = points.computeIfAbsent(claimId, TradePoint::new);
        save(p);
        return p;
    }

    /** The ClaimsLink for the landlord and the wizard (one place that talks to LoveClaims). */
    public ClaimsLink claimsLink() {
        return claims;
    }

    /** Saves a spot set by the wizard/admin and refreshes what depends on it. */
    public void saveSpots(TradePoint p) {
        save(p);
        updateIdSign(p);
        if (!p.open()) npcs.updateClosedSign(p);
    }

    /** Pays {@code coins} to a former tenant through the returns (delivered at once when online). */
    public void refundToReturns(UUID player, long coins, String reason) {
        if (coins <= 0 || player == null) return;
        try {
            repo.inTransaction(conn -> {
                repo.addReturnCoins(conn, player, coins, reason);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().severe("Возврат " + coins + " монет игроку " + player + " не записан: " + e.getMessage());
            return;
        }
        Player online = Bukkit.getPlayer(player);
        if (online != null) claimReturns(online);
    }

    // ------------------------------------------------------------------ rent reminders

    /**
     * Tells the tenant the rent is about to end (once per configured threshold) or, when it is
     * overdue, that the point is closed and will be confiscated if the till stays short. The
     * tenant gets it in chat when online and as a stored notice (shown on join) when not.
     */
    private void remindRent(TradePoint p, boolean grace) {
        if (!plugin.getMarketConfig().rentRemindersEnabled()) return;
        long now = System.currentTimeMillis();
        UUID id = p.claimId();
        String cost = plugin.getMarketStyle().money(claims.renewCost(id));
        String till = plugin.getMarketStyle().money(p.tillCoins());

        if (grace) {
            long every = plugin.getMarketConfig().rentGraceReminderMinutes() * 60_000L;
            Long last = graceRemindedAt.get(id);
            if (last == null) {
                graceRemindedAt.put(id, now); // the "closed" message itself has just gone out
                return;
            }
            if (now - last < every) return;
            graceRemindedAt.put(id, now);
            remindOwner(p, "rent-grace-reminder", "cost", cost, "till", till);
            return;
        }
        graceRemindedAt.remove(id);

        long end = rentEnd(p);
        if (end <= 0) return;
        RentReminderMath.Decision decision = RentReminderMath.decide(
                plugin.getMarketConfig().rentReminderHours(), end - now, remindedHours.get(id));
        if (decision.reset()) remindedHours.remove(id);
        if (decision.send() != null) {
            remindedHours.put(id, decision.send());
            java.util.List<String> units = plugin.getMarketMessages().lines("time-units");
            String left = DurationText.format(end - now, units.size() >= 4 ? units.toArray(new String[0])
                    : new String[]{"d", "h", "min", "<1 min"});
            remindOwner(p, "rent-reminder", "time", left, "cost", cost, "till", till);
        }
    }

    private void remindOwner(TradePoint p, String key, String... kv) {
        Player online = Bukkit.getPlayer(p.ownerUuid());
        if (online != null) {
            plugin.getMarketMessages().send(online, key, kv);
        } else {
            notice(p.ownerUuid(), plugin.getMarketMessages().raw(key, kv));
        }
    }

    // ------------------------------------------------------------------ grace / upkeep

    /**
     * Every 30 s: a shop shuts while its tenant is in the grace period (rent overdue) or has fallen
     * into a reputation class the market does not serve, and opens again by itself once that is over.
     * Reputation is judged only while the owner is ONLINE: LoveBehavior has no data for offline
     * players (they read as neutral), and judging them would reopen a banned shop every time they log off.
     */
    private void upkeep() {
        if (guards != null) guards.payroll();
        pruneTicks++;
        if (pruneTicks % 120 == 0) { // ~ hourly, and off the main thread: these are plain DELETEs
            final String dayKey = robbery == null ? null : robbery.currentDayKey();
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    if (dayKey != null) repo.pruneRobberyState(7L * 24 * 3_600_000L, dayKey);
                    int[] n = repo.pruneHistory(System.currentTimeMillis(), 365L * 24 * 3_600_000L, 90L * 24 * 3_600_000L);
                    if (n[0] + n[1] + n[2] > 0) {
                        plugin.getLogger().info("Рынок: очищено сделок " + n[0] + ", ограблений " + n[1] + ", истёкших запретов " + n[2]);
                    }
                } catch (SQLException e) {
                    plugin.getLogger().warning("Очистка истории рынка не удалась: " + e.getMessage());
                }
            });
        }
        for (TradePoint p : new ArrayList<>(points.values())) {
            if (!p.hasOwner()) continue;
            Player online = Bukkit.getPlayer(p.ownerUuid());
            boolean grace = claims.inGrace(p.claimId());
            Boolean mayOperate = online == null ? null : gate.canOperate(p.ownerUuid());
            if (online != null) tax.rateForOwner(p); // remember the rate for the hours the owner is away
            CloseReason reason = p.closeReason();
            remindRent(p, grace);

            if (grace) {
                if (p.open()) {
                    setClosed(p, CloseReason.RENT_GRACE);
                    if (online != null) plugin.getMarketMessages().send(online, "rent-grace-closed");
                }
            } else if (mayOperate != null && !mayOperate) {
                if (p.open() || reason == CloseReason.RENT_GRACE) {
                    setClosed(p, CloseReason.REPUTATION);
                    if (online != null) plugin.getMarketMessages().send(online, "shop-closed-reputation");
                }
            } else if (reason == CloseReason.RENT_GRACE) {
                setOpen(p);
                if (online != null) plugin.getMarketMessages().send(online, "rent-grace-reopened");
            } else if (reason == CloseReason.REPUTATION && mayOperate != null) {
                setOpen(p);
                plugin.getMarketMessages().send(online, "shop-reopened-reputation");
            }
        }
    }

    // ------------------------------------------------------------------ open / close

    public OpenResult toggleOpen(Player actor, TradePoint p) {
        if (!p.isOwner(actor.getUniqueId())) return OpenResult.NOT_OWNER;
        if (p.open()) {
            setClosed(p, CloseReason.OWNER);
            return OpenResult.CLOSED;
        }
        CloseReason reason = p.closeReason();
        if (reason != null && !reason.ownerMayReopen()) return OpenResult.DENIED_REASON;
        setOpen(p);
        return OpenResult.OPENED;
    }

    public void setClosed(TradePoint p, CloseReason reason) {
        p.open(false);
        p.closeReason(reason);
        save(p);
        refreshViewers(p.claimId());
        updateNpc(p);
        spawnCloseParticles(p);
    }

    public void setOpen(TradePoint p) {
        p.open(true);
        p.closeReason(null);
        save(p);
        refreshViewers(p.claimId());
        updateNpc(p);
    }

    public void updateNpc(TradePoint p) {
        if (p == null) return;
        npcs.updateStallNpc(p);
    }

    public void spawnCloseParticles(TradePoint p) {
        if (p == null || !plugin.getMarketConfig().particlesEnabled()) return;
        Location loc = getNpcOrPointLocation(p);
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().spawnParticle(Particle.SMOKE, loc.clone().add(0, 1.0, 0), 20, 0.3, 0.5, 0.3, 0.05);
        }
    }

    public void spawnRobbedParticles(TradePoint p) {
        if (p == null || !plugin.getMarketConfig().particlesEnabled()) return;
        Location loc = getNpcOrPointLocation(p);
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, loc.clone().add(0, 1.8, 0), 10, 0.3, 0.3, 0.3, 0.0);
        }
    }

    public Location getNpcOrPointLocation(TradePoint p) {
        if (p == null) return null;
        if (p.npcCitizensId() != null) {
            try {
                if (net.citizensnpcs.api.CitizensAPI.hasImplementation()) {
                    NPC npc = net.citizensnpcs.api.CitizensAPI.getNPCRegistry().getById(p.npcCitizensId());
                    if (npc != null && npc.isSpawned() && npc.getEntity() != null) {
                        return npc.getEntity().getLocation();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return claims.point(p.claimId()).map(ClaimsLink.PointInfo::location).orElse(null);
    }

    // ------------------------------------------------------------------ moderation (administrators)

    /** Closes a point by an administrator's decision; the owner cannot open it again, an administrator can. */
    public void adminClose(TradePoint p, String reason) {
        setClosed(p, CloseReason.ADMIN);
        if (p.hasOwner()) {
            notice(p.ownerUuid(), plugin.getMarketMessages().raw("notice-admin-closed",
                    "reason", reason == null || reason.isBlank() ? "-" : reason));
        }
    }

    /** @return false when the point is not closed */
    public boolean adminOpen(TradePoint p) {
        if (p.open()) return false;
        setOpen(p);
        if (p.hasOwner()) notice(p.ownerUuid(), plugin.getMarketMessages().raw("notice-admin-opened"));
        return true;
    }

    public record SeizeResult(int stacks, long items, long coins) {}

    /**
     * Confiscates a point's goods and till into the administrator's returns (they collect it like any
     * other return), and closes the point. One transaction: either everything moved or nothing did.
     *
     * @return null on error or when the point has no owner
     */
    public SeizeResult adminSeize(TradePoint p, UUID admin, String reason) {
        if (!p.hasOwner()) return null;
        UUID owner = p.ownerUuid();
        try {
            SeizeResult result = repo.inTransaction(conn -> {
                int stacks = 0;
                long items = 0;
                for (StallListing l : repo.listings(conn, p.claimId())) {
                    if (l.stock() <= 0) continue;
                    repo.addReturnItem(conn, admin, l.template(), l.stock(), "seized");
                    repo.updateListingStock(conn, l.id(), 0);
                    stacks++;
                    items += l.stock();
                }
                MarketRepository.PointState state = repo.pointState(conn, p.claimId());
                long coins = state == null ? 0 : state.till();
                if (coins > 0) {
                    repo.addTill(conn, p.claimId(), -coins, 0, 0);
                    repo.addReturnCoins(conn, admin, coins, "seized");
                }
                repo.closePoint(conn, p.claimId(), CloseReason.ADMIN);
                repo.addNotice(conn, owner, plugin.getMarketMessages().raw("notice-admin-seized",
                        "reason", reason == null || reason.isBlank() ? "-" : reason));
                return new SeizeResult(stacks, items, coins);
            });
            p.open(false);
            p.closeReason(CloseReason.ADMIN);
            refreshCounters(p.claimId());
            refreshViewers(p.claimId());
            updateNpc(p);
            spawnCloseParticles(p);
            Player ownerOnline = Bukkit.getPlayer(owner);
            if (ownerOnline != null) deliverNotices(ownerOnline);
            return result;
        } catch (SQLException e) {
            plugin.getLogger().severe("Изъятие точки " + p.claimId() + " не удалось: " + e.getMessage());
            return null;
        }
    }

    public enum RestoreStatus { OK, NOT_FOUND, ALREADY_RESTORED, ERROR }

    public record RestoreResult(RestoreStatus status, int stacks, long coins, UUID owner) {
        static RestoreResult of(RestoreStatus s) { return new RestoreResult(s, 0, 0, null); }
    }

    /**
     * Undoes a robbery for the victim: the stolen goods and coins are paid to the owner's returns
     * (the robber keeps what they took - taking it back is a separate punishment). The log row is
     * flipped {@code restored = 1} in the same transaction and only if it was 0, so the same
     * robbery can never be paid out twice.
     */
    public RestoreResult adminRestoreRobbery(long robberyId) {
        try {
            RestoreResult result = repo.inTransaction(conn -> {
                MarketRepository.RobberyLog log = repo.robberyLog(conn, robberyId);
                if (log == null) return RestoreResult.of(RestoreStatus.NOT_FOUND);
                if (!repo.markRobberyRestored(conn, robberyId)) return RestoreResult.of(RestoreStatus.ALREADY_RESTORED);
                int stacks = 0;
                for (RobberyLoot.Entry entry : RobberyLoot.parse(log.itemsJson())) {
                    ItemStack template = dev.lovelace.loveshops.utils.ItemStackConverter.itemStackFromBase64(entry.itemData());
                    if (template == null) {
                        plugin.getLogger().warning("Ограбление #" + robberyId + ": предмет не восстановлен (повреждён)");
                        continue;
                    }
                    repo.addReturnItem(conn, log.owner(), template, entry.amount(), "robbery-restored");
                    stacks++;
                }
                repo.addReturnCoins(conn, log.owner(), log.coins(), "robbery-restored");
                repo.addNotice(conn, log.owner(), plugin.getMarketMessages().raw("notice-robbery-restored"));
                return new RestoreResult(RestoreStatus.OK, stacks, log.coins(), log.owner());
            });
            if (result.status() == RestoreStatus.OK && result.owner() != null) {
                Player ownerOnline = Bukkit.getPlayer(result.owner());
                if (ownerOnline != null) deliverNotices(ownerOnline);
            }
            return result;
        } catch (SQLException e) {
            plugin.getLogger().severe("Восстановление ограбления #" + robberyId + " не удалось: " + e.getMessage());
            return RestoreResult.of(RestoreStatus.ERROR);
        }
    }

    // ------------------------------------------------------------------ till

    /** Called by LoveClaims (main thread) to pay rent while the tenant is offline. */
    public boolean payFromTill(UUID claimId, long amount) {
        TradePoint p = points.get(claimId);
        if (p == null || !p.hasOwner()) return false;
        if (amount <= 0) return true;
        if (p.tillCoins() < amount) return false;
        p.tillCoins(p.tillCoins() - amount);
        if (!save(p)) {
            p.tillCoins(p.tillCoins() + amount);
            return false;
        }
        refreshViewers(claimId);
        return true;
    }

    /** Adds income to the till (sales). Persisted before returning. */
    public boolean addToTill(TradePoint p, long amount) {
        if (amount <= 0) return true;
        p.tillCoins(p.tillCoins() + amount);
        if (!save(p)) {
            p.tillCoins(p.tillCoins() - amount);
            return false;
        }
        return true;
    }

    /**
     * Pays the till out to its owner. The database is emptied FIRST, then the coins are handed over;
     * if handing over fails the coins go to the returns instead of vanishing.
     */
    public TillResult collectTill(Player player, TradePoint p) {
        if (!p.isOwner(player.getUniqueId())) return new TillResult(false, "not-owner", 0);
        long amount = p.tillCoins();
        if (amount <= 0) return new TillResult(false, "till-empty", 0);
        LoveEconomy economy = plugin.getEconomy().orElse(null);
        if (economy == null) return new TillResult(false, "economy-down", 0);
        if (!economy.canFit(player, amount)) return new TillResult(false, "till-no-space", amount);

        p.tillCoins(0);
        if (!save(p)) {
            p.tillCoins(amount);
            return new TillResult(false, "db-error", 0);
        }
        try {
            economy.give(player, amount);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Выдача кассы " + amount + " игроку " + player.getName() + " не удалась: " + e.getMessage());
            try {
                repo.inTransaction(conn -> {
                    repo.addReturnCoins(conn, player.getUniqueId(), amount, "till-collect-failed");
                    return null;
                });
            } catch (SQLException ex) {
                plugin.getLogger().severe("Касса " + amount + " игрока " + player.getUniqueId() + " потеряна: " + ex.getMessage());
            }
            return new TillResult(false, "give-failed", amount);
        }
        refreshViewers(p.claimId());
        return new TillResult(true, null, amount);
    }

    // ------------------------------------------------------------------ shelves

    private String materialKey(ItemStack item) {
        return item.getType().name();
    }

    private ListingResult validateItemAndPrice(ItemStack item, long unitPrice) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return ListingResult.NO_ITEM;
        if (plugin.getEconomy().map(e -> e.isCoin(item)).orElse(false)) return ListingResult.IS_COIN;
        if (plugin.getForbiddenManager().isForbidden(item)) return ListingResult.FORBIDDEN;
        if (unitPrice > maxUnitPrice(item)) return ListingResult.PRICE_HIGH;
        if (unitPrice < Math.max(1L, plugin.getMarketConfig().minPrice(materialKey(item)))) return ListingResult.PRICE_LOW;
        return null;
    }

    /** Checks an item the owner holds against the shelf rules (not its price) before asking for a price. */
    public ListingResult previewItem(Player owner, TradePoint p, ItemStack item) {
        if (!p.isOwner(owner.getUniqueId())) return ListingResult.NOT_OWNER;
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return ListingResult.NO_ITEM;
        if (plugin.getEconomy().map(e -> e.isCoin(item)).orElse(false)) return ListingResult.IS_COIN;
        if (plugin.getForbiddenManager().isForbidden(item)) return ListingResult.FORBIDDEN;
        return ListingResult.OK;
    }

    /** Lowest and highest unit price allowed for an item, for the prompt text. */
    public long minUnitPrice(ItemStack item) {
        return Math.max(1L, plugin.getMarketConfig().minPrice(materialKey(item)));
    }

    public long maxUnitPrice(ItemStack item) {
        return plugin.getMarketConfig().maxPrice(materialKey(item));
    }

    /**
     * Puts the whole stack the owner holds in the main hand on shelf {@code slot}. The item leaves the
     * hand before anything is written and comes back if the write fails.
     */
    public ListingResult addSellListing(Player owner, TradePoint p, int slot, long unitPrice) {
        if (!p.isOwner(owner.getUniqueId())) return ListingResult.NOT_OWNER;
        if (slot < 0 || slot >= p.sellSlots()) return ListingResult.NO_SLOT;
        ItemStack hand = owner.getInventory().getItemInMainHand();
        ListingResult bad = validateItemAndPrice(hand, unitPrice);
        if (bad != null) return bad;

        ItemStack taken = hand.clone();
        owner.getInventory().setItemInMainHand(null);
        try {
            long id = repo.inTransaction(conn -> repo.insertListing(conn, p.claimId(), ListingType.SELL, slot, taken, unitPrice, taken.getAmount(), 0));
            if (id < 0) {
                owner.getInventory().setItemInMainHand(taken);
                return ListingResult.SLOT_TAKEN;
            }
        } catch (SQLException e) {
            owner.getInventory().setItemInMainHand(taken);
            plugin.getLogger().warning("Лот не создан: " + e.getMessage());
            return ListingResult.DB_ERROR;
        }
        refreshViewers(p.claimId());
        return ListingResult.OK;
    }

    /** Puts a specified item stack on shelf {@code slot}. */
    public ListingResult addSellListing(Player owner, TradePoint p, int slot, ItemStack item, long unitPrice) {
        if (!p.isOwner(owner.getUniqueId())) return ListingResult.NOT_OWNER;
        if (slot < 0 || slot >= p.sellSlots()) return ListingResult.NO_SLOT;
        ListingResult bad = validateItemAndPrice(item, unitPrice);
        if (bad != null) return bad;

        ItemStack taken = item.clone();
        try {
            long id = repo.inTransaction(conn -> repo.insertListing(conn, p.claimId(), ListingType.SELL, slot, taken, unitPrice, taken.getAmount(), 0));
            if (id < 0) return ListingResult.SLOT_TAKEN;
        } catch (SQLException e) {
            plugin.getLogger().warning("Лот не создан: " + e.getMessage());
            return ListingResult.DB_ERROR;
        }
        refreshViewers(p.claimId());
        return ListingResult.OK;
    }

    /** Creates a buy order: the item in hand is only a sample (it stays with the owner). */
    public ListingResult addBuyListing(Player owner, TradePoint p, int slot, long unitPrice, int maxAmount) {
        if (!p.isOwner(owner.getUniqueId())) return ListingResult.NOT_OWNER;
        if (slot < 0 || slot >= p.buySlots()) return ListingResult.NO_SLOT;
        ItemStack hand = owner.getInventory().getItemInMainHand();
        ListingResult bad = validateItemAndPrice(hand, unitPrice);
        if (bad != null) return bad;
        try {
            long id = repo.inTransaction(conn -> repo.insertListing(conn, p.claimId(), ListingType.BUY, slot, hand, unitPrice, 0, Math.max(1, maxAmount)));
            if (id < 0) return ListingResult.SLOT_TAKEN;
        } catch (SQLException e) {
            plugin.getLogger().warning("Заказ на скупку не создан: " + e.getMessage());
            return ListingResult.DB_ERROR;
        }
        refreshViewers(p.claimId());
        return ListingResult.OK;
    }

    /** Creates a buy order with a template item. */
    public ListingResult addBuyListing(Player owner, TradePoint p, int slot, ItemStack template, long unitPrice, int maxAmount) {
        if (!p.isOwner(owner.getUniqueId())) return ListingResult.NOT_OWNER;
        if (slot < 0 || slot >= p.buySlots()) return ListingResult.NO_SLOT;
        ListingResult bad = validateItemAndPrice(template, unitPrice);
        if (bad != null) return bad;
        try {
            long id = repo.inTransaction(conn -> repo.insertListing(conn, p.claimId(), ListingType.BUY, slot, template.clone(), unitPrice, 0, Math.max(1, maxAmount)));
            if (id < 0) return ListingResult.SLOT_TAKEN;
        } catch (SQLException e) {
            plugin.getLogger().warning("Заказ на скупку не создан: " + e.getMessage());
            return ListingResult.DB_ERROR;
        }
        refreshViewers(p.claimId());
        return ListingResult.OK;
    }

    public int getStorageCapacity(TradePoint p) {
        return UpgradeMath.storageCapacity(plugin.getMarketConfig().baseStorageStacks(), plugin.getMarketConfig().storagePerLevel(), p.level());
    }

    public List<dev.lovelace.loveshops.market.model.StorageItem> getStorage(TradePoint p) {
        try {
            return repo.loadStorage(p.claimId());
        } catch (SQLException e) {
            plugin.getLogger().warning("Склад точки " + p.claimId() + " не прочитан: " + e.getMessage());
            return List.of();
        }
    }

    public boolean putStorageItem(TradePoint p, int slot, ItemStack item) {
        try {
            repo.saveStorageItem(p.claimId(), slot, item);
            refreshViewers(p.claimId());
            return true;
        } catch (SQLException e) {
            plugin.getLogger().warning("Предмет не сохранён на склад: " + e.getMessage());
            return false;
        }
    }

    public ItemStack takeStorageItem(TradePoint p, int slot) {
        try {
            List<dev.lovelace.loveshops.market.model.StorageItem> items = repo.loadStorage(p.claimId());
            for (var it : items) {
                if (it.slot() == slot) {
                    repo.removeStorageItem(p.claimId(), slot);
                    refreshViewers(p.claimId());
                    return it.item();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Предмет не взят со склада: " + e.getMessage());
        }
        return null;
    }

    /** Adds the stack in the owner's hand to an existing SELL listing of the same item. */
    public ListingResult addStock(Player owner, TradePoint p, long listingId) {
        if (!p.isOwner(owner.getUniqueId())) return ListingResult.NOT_OWNER;
        ItemStack hand = owner.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return ListingResult.NO_ITEM;
        ItemStack taken = hand.clone();
        owner.getInventory().setItemInMainHand(null);
        try {
            ListingResult result = repo.inTransaction(conn -> {
                StallListing l = repo.listing(conn, listingId);
                if (l == null || !l.pointId().equals(p.claimId()) || l.type() != ListingType.SELL) return ListingResult.LISTING_GONE;
                if (!l.matches(taken)) return ListingResult.NO_ITEM;
                long total = (long) l.stock() + taken.getAmount();
                if (total > Integer.MAX_VALUE) return ListingResult.NO_SPACE;
                repo.updateListingStock(conn, listingId, (int) total);
                return ListingResult.OK;
            });
            if (result != ListingResult.OK) {
                owner.getInventory().setItemInMainHand(taken);
                return result;
            }
        } catch (SQLException e) {
            owner.getInventory().setItemInMainHand(taken);
            plugin.getLogger().warning("Товар не добавлен: " + e.getMessage());
            return ListingResult.DB_ERROR;
        }
        refreshViewers(p.claimId());
        return ListingResult.OK;
    }

    public ListingResult changePrice(Player owner, TradePoint p, long listingId, long unitPrice) {
        if (!p.isOwner(owner.getUniqueId())) return ListingResult.NOT_OWNER;
        try {
            ListingResult result = repo.inTransaction(conn -> {
                StallListing l = repo.listing(conn, listingId);
                if (l == null || !l.pointId().equals(p.claimId())) return ListingResult.LISTING_GONE;
                ListingResult bad = validateItemAndPrice(l.template(), unitPrice);
                if (bad != null) return bad;
                repo.updateListingPrice(conn, listingId, unitPrice);
                return ListingResult.OK;
            });
            if (result == ListingResult.OK) refreshViewers(p.claimId());
            return result;
        } catch (SQLException e) {
            plugin.getLogger().warning("Цена не изменена: " + e.getMessage());
            return ListingResult.DB_ERROR;
        }
    }

    /**
     * Hands the goods of a listing back to the owner: what fits goes to the inventory, the rest to
     * the returns. With {@code remove} the listing disappears; otherwise (BUY: collect what was
     * bought) it stays and only its stock is emptied. Records are updated BEFORE items are given.
     *
     * @return the number of items handed over or parked in the returns (never lost), -1 on error
     */
    public int withdraw(Player owner, TradePoint p, long listingId, boolean remove) {
        if (!p.isOwner(owner.getUniqueId())) return -1;
        try {
            record Plan(ItemStack template, int give, int park) {}
            Plan plan = repo.inTransaction(conn -> {
                StallListing l = repo.listing(conn, listingId);
                if (l == null || !l.pointId().equals(p.claimId())) return null;
                ItemStack template = l.template();
                int stock = l.stock();
                int fit = Math.min(stock, ItemTransfer.capacity(owner, template));
                int park = remove ? stock - fit : 0;
                if (park > 0) repo.addReturnItem(conn, owner.getUniqueId(), template, park, "withdraw");
                if (remove) {
                    repo.deleteListing(conn, listingId);
                } else {
                    repo.updateListingStock(conn, listingId, stock - fit);
                }
                return new Plan(template, fit, park);
            });
            if (plan == null) return -1;
            int notGiven = plan.give() > 0 ? ItemTransfer.give(owner, plan.template(), plan.give()) : 0;
            if (notGiven > 0) {
                // The inventory changed between the capacity check and the hand-over (cannot happen on
                // the main thread, but never lose items if it does): park what did not fit.
                repo.inTransaction(conn -> {
                    repo.addReturnItem(conn, owner.getUniqueId(), plan.template(), notGiven, "withdraw-overflow");
                    return null;
                });
            }
            refreshViewers(p.claimId());
            return plan.give() + plan.park();
        } catch (SQLException e) {
            plugin.getLogger().warning("Вывод товара не удался: " + e.getMessage());
            return -1;
        }
    }

    public record CollectResult(boolean ok, int items) {}

    public CollectResult collectListing(Player owner, TradePoint p, long listingId, boolean remove) {
        int count = withdraw(owner, p, listingId, remove);
        return new CollectResult(count >= 0, Math.max(0, count));
    }

    // ------------------------------------------------------------------ NPC clicks

    public boolean isMarketEntity(org.bukkit.entity.Entity entity) {
        return npcs.isMarketEntity(entity);
    }

    /**
     * Routes a click on a Citizens NPC that belongs to the market.
     *
     * @return {@code true} if the NPC is a market NPC (handled), {@code false} for any other NPC
     */
    public boolean handleNpcClick(Player player, NPC npc) {
        Optional<UUID> stall = npcs.stallPointOf(npc);
        Optional<UUID> guard = stall.isPresent() ? Optional.empty() : npcs.guardPointOf(npc);
        if (stall.isEmpty() && guard.isEmpty()) return false;

        long now = System.currentTimeMillis();
        Long last = lastNpcClick.put(player.getUniqueId(), now);
        if (lastNpcClick.size() > 128) lastNpcClick.values().removeIf(t -> now - t > 60_000L);
        if (last != null && now - last < 400L) return true;

        TradePoint p = points.get(stall.orElseGet(guard::get));
        if (p == null || !p.hasOwner()) {
            plugin.getMarketMessages().send(player, "stall-vacant");
            return true;
        }
        if (guard.isPresent()) {
            plugin.getMarketMessages().send(player, p.guardState() == GuardState.ACTIVE ? "guard-idle" : "stall-vacant");
            return true;
        }
        if (p.isOwner(player.getUniqueId())) {
            new dev.lovelace.loveshops.market.gui.StallOwnerGui(plugin, player, p).open();
            return true;
        }
        if (!p.open()) {
            plugin.getMarketMessages().send(player, "stall-closed");
            return true;
        }
        if (plugin.getMarketConfig().gatesEnabled()) {
            PlayerClass cls = gate.classify(player.getUniqueId());
            if (cls == PlayerClass.OUTCAST) {
                plugin.getMarketMessages().send(player, "outcast-refused");
                return true;
            }
            if (cls == PlayerClass.AGGRESSOR && robbery != null) {
                return handleAggressorClick(player, p);
            }
            if (cls == PlayerClass.AGGRESSOR && gate.aggressorRefused()) {
                plugin.getMarketMessages().send(player, "aggressor-refused");
                return true;
            }
        }
        new dev.lovelace.loveshops.market.gui.StallBuyerGui(plugin, player, p).open();
        return true;
    }

    /** An aggressor at a stall: refused, thrown out, or - when the dice fall - robbing it. */
    private boolean handleAggressorClick(Player player, TradePoint p) {
        var msg = plugin.getMarketMessages();
        RobberyService.Click click = robbery.onAggressorClick(player, p);
        String suffix = click.guarded() ? "guard-patience-" : "patience-";
        String[] stages = {"calm", "annoyed", "warning"};
        switch (click.outcome()) {
            case PROCEED -> new dev.lovelace.loveshops.market.gui.StallBuyerGui(plugin, player, p).open();
            case REFUSED -> msg.send(player, suffix + stages[Math.max(0, Math.min(2, click.stage()))]);
            case LOCKED -> msg.send(player, "robber-locked");
            case HOSTILE -> msg.send(player, "robber-hostile");
            case BANNED -> msg.send(player, "robber-banned");
            case KICKED -> msg.send(player, "guard-kicked");
            case ROBBED -> msg.send(player, "robbery-success");
            case NOTHING_TO_TAKE -> msg.send(player, "robbery-nothing");
        }
        return true;
    }

    /** Re-reads a point's money counters (after a recovery step changed them behind our back). */
    public void refreshCounters(UUID pointId) {
        TradePoint p = points.get(pointId);
        if (p == null) return;
        try {
            TradePoint fresh = repo.loadPoint(pointId);
            if (fresh == null) return;
            p.tillCoins(fresh.tillCoins());
            p.revenueTotal(fresh.revenueTotal());
            p.salesTotal(fresh.salesTotal());
            refreshViewers(pointId);
        } catch (SQLException e) {
            plugin.getLogger().warning("Не удалось обновить кассу точки " + pointId + ": " + e.getMessage());
        }
    }

    /** Gives the player what the market owes them and tells them what happened. */
    public void claimReturns(Player player) {
        ReturnsService.Summary s = returns.claimAll(player);
        if (s.anyDelivered()) {
            plugin.getMarketMessages().send(player, "returns-delivered",
                    "items", String.valueOf(s.items()), "money", plugin.getMarketStyle().money(s.coins()));
        }
        if (s.anyLeft()) {
            plugin.getMarketMessages().send(player, "returns-left");
        }
    }

    // ------------------------------------------------------------------ notices

    public void notice(UUID player, String message) {
        try {
            repo.addNotice(player, message);
        } catch (SQLException e) {
            plugin.getLogger().warning("Уведомление игроку " + player + " не сохранено: " + e.getMessage());
        }
    }

    /** On join: shows what happened to the player's shop while they were away. */
    public void deliverNotices(Player player) {
        try {
            for (String text : repo.takeNotices(player.getUniqueId())) {
                player.sendMessage(dev.lovelace.loveshops.utils.MessageUtils.parse(player,
                        plugin.getLangManager().getRaw("prefix", "") + text));
            }
            if (repo.countReturns(player.getUniqueId()) > 0) {
                claimReturns(player);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Уведомления игрока " + player.getName() + " не прочитаны: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ GUI registry

    public void registerViewer(MarketGui gui) {
        viewers.put(gui.viewer().getUniqueId(), gui);
    }

    public void unregisterViewer(Player player, MarketGui gui) {
        viewers.remove(player.getUniqueId(), gui);
    }



    public void refreshViewers(UUID pointId) {
        for (MarketGui gui : new ArrayList<>(viewers.values())) {
            if (pointId.equals(gui.pointId())) gui.render();
        }
    }

    public void closeViewers(UUID pointId) {
        for (MarketGui gui : new ArrayList<>(viewers.values())) {
            if (pointId.equals(gui.pointId())) gui.viewer().closeInventory();
        }
    }

    private void closeAllViewers() {
        for (MarketGui gui : new ArrayList<>(viewers.values())) gui.viewer().closeInventory();
        viewers.clear();
    }

    // ------------------------------------------------------------------ persistence

    /** @return {@code false} if the write failed (the caller keeps its in-memory state consistent) */
    public boolean save(TradePoint p) {
        try {
            repo.savePoint(p);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Точка " + p.claimId() + " не сохранена: " + e.getMessage());
            return false;
        }
    }
}
