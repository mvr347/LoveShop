package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.TaxOracle;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.TradePoint;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * Tax on player-to-player sales, taken from the SELLER's proceeds (the buyer pays exactly the
 * listed price). The rate is LoveCore's {@code TaxOracle#tradeRate} (already halved for trades
 * between players); a PERFECT seller pays nothing. The withheld coins are simply not paid out
 * (a currency sink) - there is no tax account.
 */
public final class TaxService {

    private final LoveShops plugin;
    private final ReputationGate gate;

    public TaxService(LoveShops plugin, ReputationGate gate) {
        this.plugin = plugin;
        this.gate = gate;
    }

    /** The rate right now, from LoveBehavior's live data (meaningful for online players only). */
    public double liveRate(UUID seller) {
        if (!plugin.getMarketConfig().taxEnabled()) return 0.0;
        if (gate.isTaxExempt(seller)) return 0.0;
        return LoveCore.service(TaxOracle.class).map(oracle -> oracle.tradeRate(seller)).orElse(0.0);
    }

    /** Last live rates seen while players were online; bounded so it cannot grow with every player ever seen. */
    private static final int REMEMBER_MAX = 1024;
    private final java.util.Map<UUID, Double> remembered = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<UUID, Double> eldest) {
            return size() > REMEMBER_MAX;
        }
    };

    /**
     * Rate for a seller who may be offline. Online: live, and remembered. Offline: LoveBehavior has
     * no data for them (reads neutral), so the rate seen last time they were online is used instead.
     */
    public double rateForPlayer(UUID player) {
        if (player == null) return 0.0;
        if (Bukkit.getPlayer(player) != null) {
            double rate = liveRate(player);
            remembered.put(player, rate);
            return rate;
        }
        Double last = remembered.get(player);
        return last != null ? last : liveRate(player);
    }

    /** Rate for the owner of a stall (see {@link #rateForPlayer}). */
    public double rateForOwner(TradePoint point) {
        return rateForPlayer(point.ownerUuid());
    }

    public TaxMath.Split splitForOwner(long total, TradePoint point) {
        return TaxMath.split(total, plugin.getMarketConfig().taxEnabled() ? rateForOwner(point) : 0.0);
    }

    /** For a player who is online right now (a customer selling to a stall). */
    public TaxMath.Split split(long total, UUID seller) {
        return TaxMath.split(total, liveRate(seller));
    }
}
