package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.gui.MarketGui;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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

    private final Map<UUID, TradePoint> points = new HashMap<>();
    /** Open market GUIs by viewer, so a change of a point can refresh or close what its viewers see. */
    private final Map<UUID, MarketGui> viewers = new HashMap<>();
    private BukkitTask upkeepTask;
    /** Last NPC click per player: Citizens can report one click twice (both hands) and menus must not flicker open. */
    private final Map<UUID, Long> lastNpcClick = new HashMap<>();

    public TradePointManager(LoveShops plugin, MarketRepository repo, StallNpcService npcs, ClaimsLink claims) {
        this.plugin = plugin;
        this.repo = repo;
        this.npcs = npcs;
        this.claims = claims;
    }

    // ------------------------------------------------------------------ lifecycle

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

    private void reconcileNpcs() {
        List<TradePoint> changed = npcs.reconcile(points.values(),
                id -> claims.point(id).map(ClaimsLink.PointInfo::location).orElse(null),
                p -> p.guardState() == GuardState.ACTIVE);
        for (TradePoint p : changed) save(p);
    }

    // ------------------------------------------------------------------ queries

    public Optional<TradePoint> byClaim(UUID claimId) {
        return Optional.ofNullable(points.get(claimId));
    }

    public Optional<TradePoint> byOwner(UUID owner) {
        return points.values().stream().filter(p -> p.isOwner(owner)).findFirst();
    }

    public Collection<TradePoint> all() {
        return List.copyOf(points.values());
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

    /** Rent gate for LoveClaims' cancellable request. Phase B adds the reputation checks. */
    public Optional<String> rentDenial(Player player) {
        return Optional.empty();
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
        p.sellSlots(plugin.getMarketConfig().baseSellSlots() + level - 1);
        p.buySlots(plugin.getMarketConfig().baseBuySlots() + level - 1);
        p.open(true);
        p.closeReason(null);
        p.tillCoins(0);
        p.guardState(GuardState.NONE);
        p.guardPaidUntil(0);
        p.rentedAt(System.currentTimeMillis());
        save(p);
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
        } else {
            notice(owner, plugin.getMarketMessages().raw(key));
        }
    }

    public void onExpiryWarning(UUID player, UUID claimId, long millisLeft) {
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
        npcs.destroy(npc);
        npcs.destroy(guard);
    }

    // ------------------------------------------------------------------ grace / upkeep

    /** Every 30 s: shops of tenants in the grace period shut, and reopen once the rent is paid. */
    private void upkeep() {
        for (TradePoint p : new ArrayList<>(points.values())) {
            if (!p.hasOwner()) continue;
            boolean grace = claims.inGrace(p.claimId());
            if (grace && p.open()) {
                setClosed(p, CloseReason.RENT_GRACE);
                Player online = Bukkit.getPlayer(p.ownerUuid());
                if (online != null) plugin.getMarketMessages().send(online, "rent-grace-closed");
            } else if (!grace && p.closeReason() == CloseReason.RENT_GRACE) {
                setOpen(p);
                Player online = Bukkit.getPlayer(p.ownerUuid());
                if (online != null) plugin.getMarketMessages().send(online, "rent-grace-reopened");
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
    }

    public void setOpen(TradePoint p) {
        p.open(true);
        p.closeReason(null);
        save(p);
        refreshViewers(p.claimId());
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
        if (unitPrice > plugin.getMarketConfig().priceMax()) return ListingResult.PRICE_HIGH;
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
        } else if (!p.open()) {
            plugin.getMarketMessages().send(player, "stall-closed");
        } else {
            plugin.getMarketMessages().send(player, "stall-buyer-soon");
        }
        return true;
    }

    // ------------------------------------------------------------------ notices

    private void notice(UUID player, String message) {
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
            int waiting = repo.countReturns(player.getUniqueId());
            if (waiting > 0) {
                plugin.getMarketMessages().send(player, "returns-waiting", "count", String.valueOf(waiting));
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
