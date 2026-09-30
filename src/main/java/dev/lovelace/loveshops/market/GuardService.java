package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.TradePoint;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.SQLException;

/**
 * A paid guard for a stall. The salary is a recurring payment (not a one-off): taken from the
 * stall's till, else from the owner's pockets while they are online; when neither can pay the
 * guard leaves ("unpaid") and the stall is exposed again. There is no debt - unpaid salary is not
 * accumulated.
 */
public final class GuardService {

    public enum Result { OK, NOT_OWNER, DISABLED, ALREADY, NO_MONEY, ECONOMY_DOWN, DB_ERROR }

    private final LoveShops plugin;
    private final MarketRepository repo;
    private final TradePointManager manager;

    public GuardService(LoveShops plugin, MarketRepository repo, TradePointManager manager) {
        this.plugin = plugin;
        this.repo = repo;
        this.manager = manager;
    }

    private long periodMillis() {
        return plugin.getMarketConfig().guardSalaryPeriodHours() * 3_600_000L;
    }

    public long salary() {
        return plugin.getMarketConfig().guardSalary();
    }

    public Result hire(Player owner, TradePoint p) {
        if (!p.isOwner(owner.getUniqueId())) return Result.NOT_OWNER;
        if (!plugin.getMarketConfig().guardEnabled()) return Result.DISABLED;
        if (p.guardState() == GuardState.ACTIVE) return Result.ALREADY;
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        if (eco == null) return Result.ECONOMY_DOWN;

        // The first period is paid in advance.
        Boolean paid = collectSalary(owner, p, eco);
        if (paid == null) return Result.DB_ERROR;
        if (!paid) return Result.NO_MONEY;
        long until = System.currentTimeMillis() + periodMillis();
        try {
            repo.inTransaction(conn -> {
                repo.setGuard(conn, p.claimId(), GuardState.ACTIVE.name(), until);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().severe("Найм стражи не записан для точки " + p.claimId() + ": " + e.getMessage());
            refund(owner, eco, p);
            return Result.DB_ERROR;
        }
        p.guardState(GuardState.ACTIVE);
        p.guardPaidUntil(until);
        manager.syncNpcs();
        manager.refreshViewers(p.claimId());
        return Result.OK;
    }

    public Result fire(Player owner, TradePoint p) {
        if (!p.isOwner(owner.getUniqueId())) return Result.NOT_OWNER;
        return release(p, GuardState.NONE) ? Result.OK : Result.DB_ERROR;
    }

    /** Sets the guard state (NONE/UNPAID) and removes the guard NPC. */
    private boolean release(TradePoint p, GuardState state) {
        try {
            repo.inTransaction(conn -> {
                repo.setGuard(conn, p.claimId(), state.name(), 0);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().warning("Стража точки " + p.claimId() + " не снята: " + e.getMessage());
            return false;
        }
        p.guardState(state);
        p.guardPaidUntil(0);
        manager.syncNpcs();
        manager.refreshViewers(p.claimId());
        return true;
    }

    /**
     * Pays the next salary of every guard whose paid period is over. Called every 30 s.
     * A guard that cannot be paid leaves and the owner is told.
     */
    public void payroll() {
        long now = System.currentTimeMillis();
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        for (TradePoint p : manager.all()) {
            if (!p.hasOwner() || p.guardState() != GuardState.ACTIVE || now < p.guardPaidUntil()) continue;
            Player online = Bukkit.getPlayer(p.ownerUuid());
            Boolean paid = eco == null ? Boolean.FALSE : collectSalary(online, p, eco);
            if (paid == null) continue; // database trouble: try again next tick
            if (paid) {
                long until = Math.max(p.guardPaidUntil(), now) + periodMillis();
                try {
                    repo.inTransaction(conn -> {
                        repo.setGuard(conn, p.claimId(), GuardState.ACTIVE.name(), until);
                        return null;
                    });
                    p.guardPaidUntil(until);
                    manager.refreshViewers(p.claimId());
                } catch (SQLException e) {
                    plugin.getLogger().severe("Зарплата стражи точки " + p.claimId() + " списана, но срок не записан: " + e.getMessage());
                    p.guardPaidUntil(until);
                }
            } else {
                if (release(p, GuardState.UNPAID)) {
                    if (online != null) {
                        plugin.getMarketMessages().send(online, "guard-unpaid");
                    } else {
                        manager.notice(p.ownerUuid(), plugin.getMarketMessages().raw("guard-unpaid"));
                    }
                }
            }
        }
    }

    /**
     * Takes one salary: from the till when it holds enough, else from the online owner.
     *
     * @return TRUE paid, FALSE could not pay, null on a database failure
     */
    private Boolean collectSalary(Player onlineOwner, TradePoint p, LoveEconomy eco) {
        long salary = salary();
        if (salary <= 0) return Boolean.TRUE;
        if (p.tillCoins() >= salary) {
            try {
                long[] c = repo.inTransaction(conn -> {
                    MarketRepository.PointState ps = repo.pointState(conn, p.claimId());
                    if (ps == null || ps.till() < salary) return null;
                    return repo.addTill(conn, p.claimId(), -salary, 0, 0);
                });
                if (c != null) {
                    p.tillCoins(c[0]);
                    return Boolean.TRUE;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Зарплата стражи не списана из кассы: " + e.getMessage());
                return null;
            }
        }
        if (onlineOwner != null && eco.has(onlineOwner, salary) && eco.charge(onlineOwner, salary)) {
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /** Best-effort give-back when a hire could not be recorded after the salary was already taken. */
    private void refund(Player owner, LoveEconomy eco, TradePoint p) {
        long salary = salary();
        try {
            eco.give(owner, salary);
        } catch (RuntimeException e) {
            plugin.getLogger().severe("КРИТИЧНО: игроку " + owner.getUniqueId() + " не вернули " + salary + " монет за несостоявшийся найм стражи.");
        }
    }
}
