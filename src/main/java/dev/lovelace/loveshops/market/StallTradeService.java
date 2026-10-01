package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository.PendingTrade;
import dev.lovelace.loveshops.market.MarketRepository.PointState;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.PlayerClass;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.market.model.TradingMode;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Buying from a stall and selling to a stall.
 *
 * <p>Items and coins live in two systems that cannot share one transaction (the database and a
 * player's inventory), so every trade is a short journalled sequence on the main thread:
 * reserve in the database, move the coins, move the items, settle in the database. A row in
 * {@code pending_trades} records how far it got, and {@link #reconcile} finishes or undoes whatever
 * a crash or a database failure left half-way. Nothing is ever handed to a player before the
 * database has recorded that it left the stall.</p>
 */
public final class StallTradeService {

    public enum Result {
        OK, CLOSED, SELF, BAD_REPUTATION, GONE, NOT_ENOUGH_STOCK, NO_MONEY, NO_SPACE,
        TILL_EMPTY, ORDER_FULL, NO_ITEMS, DB_ERROR, ECONOMY_DOWN, BUSY, MODE_DENIED, BLACKLISTED
    }

    /** {@code net} = what the seller side received after tax. */
    public record Outcome(Result result, int amount, long total, long tax, long net) {
        static Outcome fail(Result r) { return new Outcome(r, 0, 0, 0, 0); }
        public boolean ok() { return result == Result.OK; }
    }

    private record Reserve(Result result, long pendingId, long listingId, ItemStack template, String itemHash,
                           int amount, long total, long tax, long net, long[] counters) {
        static Reserve fail(Result r) { return new Reserve(r, -1, -1, null, null, 0, 0, 0, 0, null); }
    }

    private final LoveShops plugin;
    private final MarketRepository repo;
    private final TradePointManager manager;
    private final ReputationGate gate;
    private final TaxService tax;
    /** Players with a trade in progress: a re-entrant click (event fired by the trade itself) is refused. */
    private final Set<UUID> inFlight = new HashSet<>();

    public StallTradeService(LoveShops plugin, MarketRepository repo, TradePointManager manager,
                             ReputationGate gate, TaxService tax) {
        this.plugin = plugin;
        this.repo = repo;
        this.manager = manager;
        this.gate = gate;
        this.tax = tax;
    }

    public Result checkAccess(Player player, TradePoint p) {
        return access(player, p);
    }

    private Result access(Player player, TradePoint p) {
        if (!p.isTrading()) return Result.CLOSED;
        if (p.isOwner(player.getUniqueId())) return Result.SELF;
        if (repo.isBlacklisted(p.claimId(), player.getUniqueId())) {
            return Result.BLACKLISTED;
        }
        if (plugin.getMarketConfig().gatesEnabled() && gate.classify(player.getUniqueId()) == PlayerClass.OUTCAST) {
            return Result.BAD_REPUTATION;
        }
        return null;
    }

    // ------------------------------------------------------------------ buy from the stall

    public Outcome buy(Player buyer, TradePoint p, long listingId, int requested) {
        UUID buyerId = buyer.getUniqueId();
        if (!inFlight.add(buyerId)) return Outcome.fail(Result.BUSY);
        try {
            LoveEconomy eco = plugin.getEconomy().orElse(null);
            if (eco == null) return Outcome.fail(Result.ECONOMY_DOWN);
            if (p.tradingMode() == TradingMode.BUY_ONLY) return Outcome.fail(Result.MODE_DENIED);
            Result denied = access(buyer, p);
            if (denied != null) return Outcome.fail(denied);
            final int amount = Math.max(1, requested);
            final UUID owner = p.ownerUuid();

            Reserve r;
            try {
                r = repo.inTransaction(conn -> {
                    PointState ps = repo.pointState(conn, p.claimId());
                    if (ps == null || ps.owner() == null || !ps.owner().equals(owner) || !ps.open()) return Reserve.fail(Result.CLOSED);
                    StallListing l = repo.listing(conn, listingId);
                    if (l == null || !l.pointId().equals(p.claimId()) || l.type() != ListingType.SELL || !l.active()) {
                        return Reserve.fail(Result.GONE);
                    }
                    if (l.stock() < amount) return Reserve.fail(Result.NOT_ENOUGH_STOCK);
                    long total;
                    try {
                        total = Math.multiplyExact(l.unitPrice(), (long) amount);
                    } catch (ArithmeticException e) {
                        return Reserve.fail(Result.GONE);
                    }
                    var discount = repo.getDiscount(p.claimId(), buyerId);
                    if (discount != null && discount.percent() > 0) {
                        long discountAmount = total * Math.min(plugin.getMarketConfig().discountMaxPercent(), discount.percent()) / 100L;
                        total = Math.max(1L, total - discountAmount);
                    }
                    if (!eco.has(buyer, total)) return Reserve.fail(Result.NO_MONEY);
                    ItemStack template = l.template();
                    if (ItemTransfer.capacity(buyer, template) < amount) return Reserve.fail(Result.NO_SPACE);
                    TaxMath.Split split = tax.splitForOwner(total, p);
                    repo.addListingStock(conn, l.id(), -amount);
                    long pid = repo.insertPending(conn, "BUY", buyerId, p.claimId(), l.id(), amount, total, split.tax(), split.net());
                    return new Reserve(Result.OK, pid, l.id(), template, l.itemHash(), amount, total, split.tax(), split.net(), null);
                });
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warning("Покупка не начата: " + e.getMessage());
                return Outcome.fail(Result.DB_ERROR);
            }
            if (r.result() != Result.OK) return Outcome.fail(r.result());

            // 1) the coins
            boolean charged;
            try {
                charged = eco.charge(buyer, r.total());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Списание монет не удалось: " + e.getMessage());
                charged = false;
            }
            if (!charged) {
                undoBuyReservation(r);
                return Outcome.fail(Result.NO_MONEY);
            }
            try {
                repo.inTransaction(conn -> {
                    repo.setPendingState(conn, r.pendingId(), "CHARGED");
                    return null;
                });
            } catch (SQLException e) {
                // Cannot record that the coins are taken: give them back and undo, rather than risk a lost sale.
                plugin.getLogger().severe("Покупка #" + r.pendingId() + ": не записано списание, возвращаю деньги: " + e.getMessage());
                try { eco.give(buyer, r.total()); } catch (RuntimeException ex) { logLost(buyerId, r.total(), r.pendingId()); }
                undoBuyReservation(r);
                return Outcome.fail(Result.DB_ERROR);
            }

            // 2) the items
            int notGiven = ItemTransfer.give(buyer, r.template(), r.amount());

            // 3) settle: the owner's till, statistics, the log, the journal - one transaction
            try {
                long[] counters = repo.inTransaction(conn -> {
                    long[] c = repo.addTill(conn, p.claimId(), r.net(), r.total(), r.amount());
                    repo.insertTransaction(conn, "SALE", p.claimId(), owner, buyerId, r.itemHash(), r.amount(), r.total(), r.tax());
                    repo.addTraderProgress(conn, owner, r.amount(), 0);
                    repo.addTraderProgress(conn, buyerId, 0, r.amount());
                    if (notGiven > 0) repo.addReturnItem(conn, buyerId, r.template(), notGiven, "trade-overflow");
                    repo.setPendingState(conn, r.pendingId(), "DONE");
                    return c;
                });
                if (counters != null) applyCounters(p, counters);
            } catch (SQLException e) {
                // Coins are taken and items given; the journal row stays CHARGED and reconcile() credits the till.
                plugin.getLogger().severe("Покупка #" + r.pendingId() + ": не закрыта в БД, будет доведена при сверке: " + e.getMessage());
            }

            manager.refreshViewers(p.claimId());
            notifyOwner(owner, buyer.getName(), r, true);
            spawnTradeParticles(buyer, p);
            return new Outcome(Result.OK, r.amount(), r.total(), r.tax(), r.net());
        } finally {
            inFlight.remove(buyerId);
        }
    }

    private void undoBuyReservation(Reserve r) {
        try {
            repo.inTransaction(conn -> {
                repo.addListingStock(conn, r.listingId(), r.amount());
                repo.setPendingState(conn, r.pendingId(), "ROLLED_BACK");
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().severe("Покупка #" + r.pendingId() + ": не удалось вернуть товар на полку, вернёт сверка: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ sell to the stall

    public Outcome sell(Player seller, TradePoint p, long listingId, int requested) {
        UUID sellerId = seller.getUniqueId();
        if (!inFlight.add(sellerId)) return Outcome.fail(Result.BUSY);
        try {
            LoveEconomy eco = plugin.getEconomy().orElse(null);
            if (eco == null) return Outcome.fail(Result.ECONOMY_DOWN);
            if (p.tradingMode() == TradingMode.SELL_ONLY) return Outcome.fail(Result.MODE_DENIED);
            Result denied = access(seller, p);
            if (denied != null) return Outcome.fail(denied);
            final int wanted = Math.max(1, requested);
            final UUID owner = p.ownerUuid();

            Reserve r;
            try {
                r = repo.inTransaction(conn -> {
                    PointState ps = repo.pointState(conn, p.claimId());
                    if (ps == null || ps.owner() == null || !ps.owner().equals(owner) || !ps.open()) return Reserve.fail(Result.CLOSED);
                    StallListing l = repo.listing(conn, listingId);
                    if (l == null || !l.pointId().equals(p.claimId()) || l.type() != ListingType.BUY || !l.active()) {
                        return Reserve.fail(Result.GONE);
                    }
                    ItemStack template = l.template();
                    int have = ItemTransfer.count(seller, template);
                    if (have <= 0) return Reserve.fail(Result.NO_ITEMS);
                    int room = l.freeCapacity();
                    if (room <= 0) return Reserve.fail(Result.ORDER_FULL);
                    long affordable = ps.till() / Math.max(1L, l.unitPrice());
                    if (affordable <= 0) return Reserve.fail(Result.TILL_EMPTY);
                    int amount = (int) Math.min(Math.min((long) wanted, have), Math.min((long) room, affordable));
                    long pay = Math.multiplyExact(l.unitPrice(), (long) amount);
                    TaxMath.Split split = tax.split(pay, sellerId);
                    if (!eco.canFit(seller, split.net())) return Reserve.fail(Result.NO_SPACE);
                    long[] counters = repo.addTill(conn, p.claimId(), -pay, 0, 0);
                    repo.addListingStock(conn, l.id(), amount);
                    long pid = repo.insertPending(conn, "SELL", sellerId, p.claimId(), l.id(), amount, pay, split.tax(), split.net());
                    return new Reserve(Result.OK, pid, l.id(), template, l.itemHash(), amount, pay, split.tax(), split.net(), counters);
                });
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warning("Продажа не начата: " + e.getMessage());
                return Outcome.fail(Result.DB_ERROR);
            }
            if (r.result() != Result.OK) return Outcome.fail(r.result());
            if (r.counters() != null) applyCounters(p, r.counters());

            // 1) the items leave the seller
            int removed = ItemTransfer.remove(seller, r.template(), r.amount());
            if (removed < r.amount()) {
                if (removed > 0) ItemTransfer.give(seller, r.template(), removed);
                undoSellReservation(p, r);
                return Outcome.fail(Result.NO_ITEMS);
            }
            // 2) the coins reach the seller (fit was checked in the reservation)
            try {
                eco.give(seller, r.net());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Выдача монет за продажу не удалась: " + e.getMessage());
                ItemTransfer.give(seller, r.template(), r.amount());
                undoSellReservation(p, r);
                return Outcome.fail(Result.DB_ERROR);
            }
            // 3) settle
            try {
                repo.inTransaction(conn -> {
                    repo.insertTransaction(conn, "BUYOUT", p.claimId(), sellerId, owner, r.itemHash(), r.amount(), r.total(), r.tax());
                    repo.addTraderProgress(conn, sellerId, r.amount(), 0);
                    repo.addTraderProgress(conn, owner, 0, r.amount());
                    repo.setPendingState(conn, r.pendingId(), "DONE");
                    return null;
                });
            } catch (SQLException e) {
                // Money and items already moved for real; only the log is missing. Do NOT roll back.
                plugin.getLogger().severe("Продажа #" + r.pendingId() + ": запись журнала не удалась (сделка состоялась): " + e.getMessage());
            }
            manager.refreshViewers(p.claimId());
            notifyOwner(owner, seller.getName(), r, false);
            spawnTradeParticles(seller, p);
            return new Outcome(Result.OK, r.amount(), r.total(), r.tax(), r.net());
        } finally {
            inFlight.remove(sellerId);
        }
    }

    private void spawnTradeParticles(Player player, TradePoint p) {
        if (!plugin.getMarketConfig().particlesEnabled()) return;
        if (player != null && player.isOnline()) {
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1.8, 0), 10, 0.3, 0.3, 0.3, 0.0);
        }
        Location loc = manager.getNpcOrPointLocation(p);
        if (loc != null && loc.getWorld() != null) {
            loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 1.8, 0), 10, 0.3, 0.3, 0.3, 0.0);
        }
    }

    private void undoSellReservation(TradePoint p, Reserve r) {
        try {
            long[] counters = repo.inTransaction(conn -> {
                long[] c = repo.addTill(conn, p.claimId(), r.total(), 0, 0);
                repo.addListingStock(conn, r.listingId(), -r.amount());
                repo.setPendingState(conn, r.pendingId(), "ROLLED_BACK");
                return c;
            });
            if (counters != null) applyCounters(p, counters);
        } catch (SQLException e) {
            plugin.getLogger().severe("Продажа #" + r.pendingId() + ": не удалось вернуть кассу, вернёт сверка: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Finishes or undoes trades a crash or a database failure left half-way (rows older than
     * {@code olderThanMillis}; at startup everything unfinished is stale).
     */
    public void reconcile(long olderThanMillis) {
        try {
            for (PendingTrade t : repo.unfinishedPending(olderThanMillis)) {
                try {
                    repo.inTransaction(conn -> {
                        switch (t.kind() + ":" + t.state()) {
                            // Reserved, coins not (recorded as) taken: the goods go back on the shelf.
                            case "BUY:RESERVED" -> {
                                repo.addListingStock(conn, t.listingId(), t.amount());
                                repo.setPendingState(conn, t.id(), "ROLLED_BACK_RECOVERY");
                            }
                            // Coins were taken and the items handed over: credit the stall and close the row.
                            case "BUY:CHARGED" -> {
                                PointState ps = repo.pointState(conn, t.pointId());
                                if (ps != null && ps.owner() != null) {
                                    repo.addTill(conn, t.pointId(), t.sellerGets(), t.total(), t.amount());
                                    repo.insertTransaction(conn, "SALE", t.pointId(), ps.owner(), t.player(), null, t.amount(), t.total(), t.tax());
                                }
                                repo.setPendingState(conn, t.id(), "DONE_RECOVERY");
                            }
                            // Till was debited but the seller's items/coins may not have moved: give the till back.
                            case "SELL:RESERVED" -> {
                                repo.addTill(conn, t.pointId(), t.total(), 0, 0);
                                repo.addListingStock(conn, t.listingId(), -t.amount());
                                repo.setPendingState(conn, t.id(), "ROLLED_BACK_RECOVERY");
                            }
                            // Flea lot reserved, coins not (recorded as) taken: the goods go back on the lot.
                            case "FLEA:RESERVED" -> {
                                repo.addFleaAmount(conn, t.listingId(), t.amount());
                                repo.setPendingState(conn, t.id(), "ROLLED_BACK_RECOVERY");
                            }
                            // Coins taken and items handed over: pay the seller (as a return) and close the row.
                            case "FLEA:CHARGED" -> {
                                MarketRepository.FleaListing f = repo.fleaListing(conn, t.listingId());
                                if (f != null) {
                                    repo.addReturnCoins(conn, f.seller(), t.sellerGets(), "flea-sale-recovery");
                                    repo.insertTransaction(conn, "FLEA", null, f.seller(), t.player(), f.itemHash(), t.amount(), t.total(), t.tax());
                                    repo.deleteFleaIfEmpty(conn, t.listingId());
                                }
                                repo.setPendingState(conn, t.id(), "DONE_RECOVERY");
                            }
                            default -> repo.setPendingState(conn, t.id(), "ABANDONED");
                        }
                        return null;
                    });
                    plugin.getLogger().warning("Сверка сделок: #" + t.id() + " (" + t.kind() + "/" + t.state() + ") приведена в порядок.");
                    if (t.pointId() != null) manager.refreshCounters(t.pointId());
                } catch (SQLException e) {
                    plugin.getLogger().severe("Сверка сделки #" + t.id() + " не удалась: " + e.getMessage());
                }
            }
            repo.purgeFinishedPending(30L * 24 * 3_600_000L);
        } catch (SQLException e) {
            plugin.getLogger().warning("Сверка сделок не прочитала журнал: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private void applyCounters(TradePoint p, long[] counters) {
        p.tillCoins(counters[0]);
        p.revenueTotal(counters[1]);
        p.salesTotal(counters[2]);
    }

    private void logLost(UUID player, long amount, long pendingId) {
        plugin.getLogger().severe("КРИТИЧНО: игроку " + player + " не удалось вернуть " + amount + " монет по сделке #" + pendingId
                + " — вернуть вручную.");
    }

    private void notifyOwner(UUID owner, String otherName, Reserve r, boolean sold) {
        Player online = Bukkit.getPlayer(owner);
        if (online == null) return;
        String itemName = r.template().getType().name();
        plugin.getMarketMessages().send(online, sold ? "notice-sold" : "notice-bought",
                "player", otherName, "item", itemName, "amount", String.valueOf(r.amount()),
                "money", plugin.getMarketStyle().money(sold ? r.net() : r.total()));
    }
}
