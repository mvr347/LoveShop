package dev.lovelace.loveshops.market;

import org.bukkit.Location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the market needs from LoveClaims, without LoveClaims types. LoveClaims is a soft dependency:
 * the only class that touches its API is {@link ClaimsBridge}, loaded only when the plugin is there.
 */
public interface ClaimsLink {

    /**
     * A trade point as LoveClaims sees it.
     *
     * @param tenant the current tenant (rented or inside the grace period), {@code null} when free
     */
    record PointInfo(UUID claimId, String name, UUID tenant, Location location, long rentEnd) {}

    List<PointInfo> tradePoints();

    Optional<PointInfo> point(UUID claimId);

    /** The tenant's term is over and only the grace period keeps them on the point. */
    boolean inGrace(UUID claimId);

    /** What the next rental period costs (0 when unknown). */
    long renewCost(UUID claimId);

    /** Hooks LoveClaims' events and rent payer to {@code manager}. */
    void register(TradePointManager manager);

    void unregister();

    /** Reassigns tenant without releasing trade point. */
    boolean transferTenant(UUID claimId, UUID newTenant);

    /** Finds the trade point at location, if any. */
    Optional<UUID> pointAt(Location loc);
}
