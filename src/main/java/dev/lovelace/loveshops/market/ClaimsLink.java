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
    record PointInfo(UUID claimId, String name, UUID tenant, Location location, long rentEnd, long price) {}

    enum RentStatus { OK, NOT_FOUND, BAD_PERIODS, DENIED, NO_ECONOMY, NO_FUNDS, NOT_TENANT }

    /** @param message the refusal text for DENIED; @param cost what was charged (OK) or was missing (NO_FUNDS) */
    record RentResult(RentStatus status, net.kyori.adventure.text.Component message, long cost) {
        public boolean ok() { return status == RentStatus.OK; }
    }

    enum CreateStatus { OK, BAD_ID, ID_TAKEN, OVERLAP, BAD_PRICE, FAILED }

    record CreateResult(CreateStatus status, UUID claimId) {
        public boolean ok() { return status == CreateStatus.OK; }
    }

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

    /** A trade point by its id (the plot name). */
    Optional<PointInfo> byName(String id);

    /** Creates a free trade point; see LoveClaimsAPI#createTradePoint. */
    CreateResult create(String id, org.bukkit.World world, Location corner1, Location corner2, Location home, long price);

    /** Deletes a trade point for good (its tenant loses it, the deleted event follows). */
    boolean delete(UUID claimId);

    /** Admin: gives the point to {@code tenant} until {@code endTime}, no payment. */
    void assign(UUID claimId, UUID tenant, long endTime);

    /** Takes the point back from its tenant; {@code reason} is a LoveClaims ReleaseReason name. */
    void release(UUID claimId, String reason);

    boolean setPrice(UUID claimId, long price);

    /** Rents a free point to the player for {@code periods} periods, paid from the player's coins. */
    RentResult rent(org.bukkit.entity.Player player, UUID claimId, int periods);

    /** The tenant prepays {@code periods} more periods. */
    RentResult extend(org.bukkit.entity.Player player, UUID claimId, int periods);

    /** Price of renting a free point for {@code periods} periods. */
    long rentCost(UUID claimId, int periods);

    /** The most periods a free point may be rented for at once. */
    int maxRentPeriods();

    /** How many more periods the tenant may add now. */
    int maxExtendPeriods(UUID claimId);

    long periodMillis();

    long graceMillis();

    boolean spawnTaxer(Location location);

    int removeTaxers();

    Optional<Location> taxerLocation();
}
