package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.TradePoint;
import org.bukkit.entity.Player;

import java.sql.SQLException;

/**
 * Raises a trader one level: one more sell shelf and one more buy order. Paid from the stall's
 * till when it holds enough, otherwise from the owner's own pockets - never from anyone else's money.
 */
public final class StallUpgradeService {

    public enum Result { OK, NOT_OWNER, MAX_LEVEL, NO_MONEY, ECONOMY_DOWN, DB_ERROR }

    private final LoveShops plugin;
    private final MarketRepository repo;

    public StallUpgradeService(LoveShops plugin, MarketRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    public boolean atMax(TradePoint p) {
        return p.level() >= plugin.getMarketConfig().maxLevel();
    }

    /** Price of the next level, or -1 at the top. */
    public long nextCost(TradePoint p) {
        if (atMax(p)) return -1L;
        return UpgradeMath.cost(p.level(), plugin.getMarketConfig().upgradeCostBase(), plugin.getMarketConfig().upgradeCostMultiplier());
    }

    public Result upgrade(Player owner, TradePoint p) {
        if (!p.isOwner(owner.getUniqueId())) return Result.NOT_OWNER;
        if (atMax(p)) return Result.MAX_LEVEL;
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null) return Result.ECONOMY_DOWN;
        final long cost = nextCost(p);
        final boolean fromTill = p.tillCoins() >= cost;
        if (!fromTill) {
            if (!eco.has(owner, cost) || !eco.charge(owner, cost)) return Result.NO_MONEY;
        }
        final int newLevel = p.level() + 1;
        final int sell = UpgradeMath.slots(plugin.getMarketConfig().baseSellSlots(), newLevel);
        final int buy = UpgradeMath.slots(plugin.getMarketConfig().baseBuySlots(), newLevel);
        try {
            long[] counters = repo.inTransaction(conn -> {
                long[] c = null;
                if (fromTill) {
                    MarketRepository.PointState ps = repo.pointState(conn, p.claimId());
                    if (ps == null || ps.till() < cost) throw new SQLException("till changed");
                    c = repo.addTill(conn, p.claimId(), -cost, 0, 0);
                }
                repo.setPointLevel(conn, p.claimId(), newLevel, sell, buy);
                repo.setTraderLevel(conn, owner.getUniqueId(), newLevel);
                return c;
            });
            p.level(newLevel);
            p.sellSlots(sell);
            p.buySlots(buy);
            if (counters != null) p.tillCoins(counters[0]);
            plugin.getTradePointManager().refreshViewers(p.claimId());
            return Result.OK;
        } catch (SQLException e) {
            plugin.getLogger().warning("Улучшение не записано: " + e.getMessage());
            if (!fromTill) {
                try {
                    eco.give(owner, cost);
                } catch (RuntimeException ex) {
                    plugin.getLogger().severe("КРИТИЧНО: игроку " + owner.getUniqueId() + " не вернули " + cost + " монет за неудавшееся улучшение.");
                }
            }
            return Result.DB_ERROR;
        }
    }
}
