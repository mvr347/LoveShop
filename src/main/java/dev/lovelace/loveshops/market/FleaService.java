package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository.FleaListing;
import dev.lovelace.loveshops.market.model.PlayerClass;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The flea trader: any player puts a stack up for sale without renting anything and another player
 * buys it. Trades follow the same journalled sequence as stall trades (reserve, coins, items,
 * settle). The seller may be offline, so the proceeds go to their returns (delivered on login or
 * from the trader's "my things" tab), taxed like any player-to-player sale.
 */
public final class FleaService {

    public enum Result {
        OK, NO_ITEM, IS_COIN, FORBIDDEN, PRICE_LOW, PRICE_HIGH, LIMIT, GONE, SELF, NOT_ENOUGH,
        NO_MONEY, NO_SPACE, BAD_REPUTATION, DB_ERROR, ECONOMY_DOWN, BUSY
    }

    public record Outcome(Result result, int amount, long total, long tax, long net) {
        static Outcome fail(Result r) { return new Outcome(r, 0, 0, 0, 0); }
        public boolean ok() { return result == Result.OK; }
    }

    private record Reserve(Result result, long pendingId, FleaListing listing, int amount, long total, long tax, long net) {
        static Reserve fail(Result r) { return new Reserve(r, -1, null, 0, 0, 0, 0); }
    }

    private final LoveShops plugin;
    private final MarketRepository repo;
    private final TradePointManager manager;
    private final ReputationGate gate;
    private final TaxService tax;
    private final ReturnsService returns;
    private final Set<UUID> inFlight = new HashSet<>();

    public FleaService(LoveShops plugin, MarketRepository repo, TradePointManager manager, ReputationGate gate,
                       TaxService tax, ReturnsService returns) {
        this.plugin = plugin;
        this.repo = repo;
        this.manager = manager;
        this.gate = gate;
        this.tax = tax;
        this.returns = returns;
    }

    public int page(int page, int size, java.util.function.Consumer<List<FleaListing>> sink) {
        try {
            sink.accept(repo.fleaPage(page * size, size));
            return repo.fleaCount();
        } catch (SQLException e) {
            plugin.getLogger().warning("Лоты барахолки не прочитаны: " + e.getMessage());
            sink.accept(List.of());
            return 0;
        }
    }

    public List<FleaListing> mine(UUID seller) {
        try {
            return repo.fleaBySeller(seller);
        } catch (SQLException e) {
            plugin.getLogger().warning("Лоты игрока " + seller + " не прочитаны: " + e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ putting up / taking down

    /** Checks the item in hand against the flea rules before asking for a price. */
    public Result previewItem(Player seller) {
        ItemStack hand = seller.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return Result.NO_ITEM;
        if (plugin.getEconomy().map(e -> e.isCoin(hand)).orElse(false)) return Result.IS_COIN;
        if (plugin.getForbiddenManager().isForbidden(hand)) return Result.FORBIDDEN;
        return Result.OK;
    }

    public long minUnitPrice(ItemStack item) {
        return Math.max(1L, plugin.getMarketConfig().minPrice(item.getType().name()));
    }

    /** Puts the whole stack from the main hand up for sale. The hand is emptied first and refilled on any failure. */
    public Result addListing(Player seller, long unitPrice) {
        Result pre = previewItem(seller);
        if (pre != Result.OK) return pre;
        ItemStack hand = seller.getInventory().getItemInMainHand();
        if (unitPrice > plugin.getMarketConfig().priceMax()) return Result.PRICE_HIGH;
        if (unitPrice < minUnitPrice(hand)) return Result.PRICE_LOW;

        ItemStack taken = hand.clone();
        seller.getInventory().setItemInMainHand(null);
        UUID id = seller.getUniqueId();
        try {
            Result r = repo.inTransaction(conn -> {
                if (repo.fleaCountBySeller(conn, id) >= plugin.getMarketConfig().fleaMaxListings()) return Result.LIMIT;
                repo.insertFlea(conn, id, taken, unitPrice, taken.getAmount());
                return Result.OK;
            });
            if (r != Result.OK) seller.getInventory().setItemInMainHand(taken);
            else manager.refreshFleaViewers();
            return r;
        } catch (SQLException e) {
            seller.getInventory().setItemInMainHand(taken);
            plugin.getLogger().warning("Лот барахолки не создан: " + e.getMessage());
            return Result.DB_ERROR;
        }
    }

    public Result changePrice(Player seller, long listingId, long unitPrice) {
        if (unitPrice > plugin.getMarketConfig().priceMax()) return Result.PRICE_HIGH;
        try {
            Result r = repo.inTransaction(conn -> {
                FleaListing f = repo.fleaListing(conn, listingId);
                if (f == null || !f.seller().equals(seller.getUniqueId()) || !f.active()) return Result.GONE;
                if (unitPrice < minUnitPrice(f.template())) return Result.PRICE_LOW;
                repo.setFleaPrice(conn, listingId, unitPrice);
                return Result.OK;
            });
            if (r == Result.OK) manager.refreshFleaViewers();
            return r;
        } catch (SQLException e) {
            plugin.getLogger().warning("Цена лота барахолки не изменена: " + e.getMessage());
            return Result.DB_ERROR;
        }
    }

    /** Takes a lot down: what fits goes to the seller's inventory, the rest to their returns. */
    public int cancel(Player seller, long listingId) {
        try {
            record Plan(ItemStack template, int give) {}
            Plan plan = repo.inTransaction(conn -> {
                FleaListing f = repo.fleaListing(conn, listingId);
                if (f == null || !f.seller().equals(seller.getUniqueId())) return null;
                int fit = Math.min(f.amountLeft(), ItemTransfer.capacity(seller, f.template()));
                int park = f.amountLeft() - fit;
                if (park > 0) repo.addReturnItem(conn, seller.getUniqueId(), f.template(), park, "flea-cancel");
                repo.deleteFlea(conn, listingId);
                return new Plan(f.template(), fit);
            });
            if (plan == null) return -1;
            int notGiven = plan.give() > 0 ? ItemTransfer.give(seller, plan.template(), plan.give()) : 0;
            if (notGiven > 0) {
                repo.inTransaction(conn -> {
                    repo.addReturnItem(conn, seller.getUniqueId(), plan.template(), notGiven, "flea-cancel-overflow");
                    return null;
                });
            }
            manager.refreshFleaViewers();
            return plan.give();
        } catch (SQLException e) {
            plugin.getLogger().warning("Лот барахолки не снят: " + e.getMessage());
            return -1;
        }
    }

    // ------------------------------------------------------------------ buying

    public Outcome buy(Player buyer, long listingId, int requested) {
        UUID buyerId = buyer.getUniqueId();
        if (!inFlight.add(buyerId)) return Outcome.fail(Result.BUSY);
        try {
            LoveEconomy eco = plugin.getEconomy().orElse(null);
            if (eco == null) return Outcome.fail(Result.ECONOMY_DOWN);
            if (plugin.getMarketConfig().gatesEnabled() && gate.classify(buyerId) == PlayerClass.OUTCAST) {
                return Outcome.fail(Result.BAD_REPUTATION);
            }
            final int amount = Math.max(1, requested);

            Reserve r;
            try {
                r = repo.inTransaction(conn -> {
                    FleaListing f = repo.fleaListing(conn, listingId);
                    if (f == null || !f.active()) return Reserve.fail(Result.GONE);
                    if (f.seller().equals(buyerId)) return Reserve.fail(Result.SELF);
                    if (f.amountLeft() < amount) return Reserve.fail(Result.NOT_ENOUGH);
                    long total;
                    try {
                        total = Math.multiplyExact(f.unitPrice(), (long) amount);
                    } catch (ArithmeticException e) {
                        return Reserve.fail(Result.GONE);
                    }
                    if (!eco.has(buyer, total)) return Reserve.fail(Result.NO_MONEY);
                    if (ItemTransfer.capacity(buyer, f.template()) < amount) return Reserve.fail(Result.NO_SPACE);
                    TaxMath.Split split = tax.splitForSeller(total, f.seller());
                    repo.addFleaAmount(conn, f.id(), -amount);
                    long pid = repo.insertPending(conn, "FLEA", buyerId, null, f.id(), amount, total, split.tax(), split.net());
                    return new Reserve(Result.OK, pid, f, amount, total, split.tax(), split.net());
                });
            } catch (SQLException | RuntimeException e) {
                plugin.getLogger().warning("Покупка на барахолке не начата: " + e.getMessage());
                return Outcome.fail(Result.DB_ERROR);
            }
            if (r.result() != Result.OK) return Outcome.fail(r.result());

            boolean charged;
            try {
                charged = eco.charge(buyer, r.total());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Списание монет на барахолке не удалось: " + e.getMessage());
                charged = false;
            }
            if (!charged) {
                undo(r);
                return Outcome.fail(Result.NO_MONEY);
            }
            try {
                repo.inTransaction(conn -> {
                    repo.setPendingState(conn, r.pendingId(), "CHARGED");
                    return null;
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("Покупка на барахолке #" + r.pendingId() + ": списание не записано, возвращаю деньги: " + e.getMessage());
                try { eco.give(buyer, r.total()); } catch (RuntimeException ex) {
                    plugin.getLogger().severe("КРИТИЧНО: игроку " + buyerId + " не вернули " + r.total() + " монет по сделке #" + r.pendingId());
                }
                undo(r);
                return Outcome.fail(Result.DB_ERROR);
            }

            int notGiven = ItemTransfer.give(buyer, r.listing().template(), r.amount());
            UUID seller = r.listing().seller();
            try {
                repo.inTransaction(conn -> {
                    repo.addReturnCoins(conn, seller, r.net(), "flea-sale");
                    repo.insertTransaction(conn, "FLEA", null, seller, buyerId, r.listing().itemHash(), r.amount(), r.total(), r.tax());
                    repo.addTraderProgress(conn, seller, r.amount(), 0);
                    repo.addTraderProgress(conn, buyerId, 0, r.amount());
                    if (notGiven > 0) repo.addReturnItem(conn, buyerId, r.listing().template(), notGiven, "trade-overflow");
                    repo.deleteFleaIfEmpty(conn, r.listing().id());
                    repo.setPendingState(conn, r.pendingId(), "DONE");
                    return null;
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("Покупка на барахолке #" + r.pendingId() + ": не закрыта в БД, будет доведена при сверке: " + e.getMessage());
            }

            manager.refreshFleaViewers();
            Player sellerOnline = Bukkit.getPlayer(seller);
            if (sellerOnline != null) {
                plugin.getMarketMessages().send(sellerOnline, "flea-sold", "player", buyer.getName(),
                        "item", r.listing().template().getType().name(), "amount", String.valueOf(r.amount()),
                        "money", plugin.getMarketStyle().money(r.net()));
                manager.claimReturns(sellerOnline);
            }
            return new Outcome(Result.OK, r.amount(), r.total(), r.tax(), r.net());
        } finally {
            inFlight.remove(buyerId);
        }
    }

    private void undo(Reserve r) {
        try {
            repo.inTransaction(conn -> {
                repo.addFleaAmount(conn, r.listing().id(), r.amount());
                repo.setPendingState(conn, r.pendingId(), "ROLLED_BACK");
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().severe("Покупка на барахолке #" + r.pendingId() + ": не удалось вернуть товар на лот, вернёт сверка: " + e.getMessage());
        }
    }

    /** What the market currently owes the player (for the "my things" tab). */
    public List<MarketRepository.ReturnEntry> returnsOf(UUID player) {
        try {
            return repo.returnsFor(player);
        } catch (SQLException e) {
            plugin.getLogger().warning("Возвраты игрока " + player + " не прочитаны: " + e.getMessage());
            return List.of();
        }
    }

    /** Hands the player everything the market owes them (their sales proceeds, cancelled lots...). */
    public ReturnsService.Summary claimAll(Player player) {
        return returns.claimAll(player);
    }
}
