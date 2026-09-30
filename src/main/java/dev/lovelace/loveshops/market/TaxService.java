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

    /**
     * Rate for the owner of a stall. Online: live, and remembered. Offline: LoveBehavior has no data
     * for them (reads neutral), so the rate seen last time they were online is used instead.
     */
    public double rateForOwner(TradePoint point) {
        UUID owner = point.ownerUuid();
        if (owner == null) return 0.0;
        if (Bukkit.getPlayer(owner) != null) {
            double rate = liveRate(owner);
            point.lastTaxRate(rate);
            return rate;
        }
        Double remembered = point.lastTaxRate();
        return remembered != null ? remembered : liveRate(owner);
    }

    public TaxMath.Split splitForOwner(long total, TradePoint point) {
        return TaxMath.split(total, plugin.getMarketConfig().taxEnabled() ? rateForOwner(point) : 0.0);
    }

    /** For a player who is online right now (a customer selling to a stall). */
    public TaxMath.Split split(long total, UUID seller) {
        return TaxMath.split(total, liveRate(seller));
    }
}
