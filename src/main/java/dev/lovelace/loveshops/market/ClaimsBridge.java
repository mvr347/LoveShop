package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import me.lovelace.loveclaims.api.LoveClaimsAPI;
import me.lovelace.loveclaims.api.TradePointRentPayer;
import me.lovelace.loveclaims.api.event.TradePointExpiryWarningEvent;
import me.lovelace.loveclaims.api.event.TradePointDeletedEvent;
import me.lovelace.loveclaims.api.event.TradePointReleasedEvent;
import me.lovelace.loveclaims.api.event.TradePointRentRequestEvent;
import me.lovelace.loveclaims.api.event.TradePointRentedEvent;
import me.lovelace.loveclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The only place that talks to LoveClaims. Instantiated only when LoveClaims is enabled. */
public final class ClaimsBridge implements ClaimsLink, Listener {

    private final LoveShops plugin;
    private TradePointManager manager;
    private TradePointRentPayer payer;

    public ClaimsBridge(LoveShops plugin) {
        this.plugin = plugin;
    }

    private static LoveClaimsAPI api() {
        return LoveClaimsAPI.isInitialized() ? LoveClaimsAPI.getInstance() : null;
    }

    @Override
    public void register(TradePointManager manager) {
        this.manager = manager;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        LoveClaimsAPI api = api();
        if (api != null) {
            this.payer = (claim, amount) -> manager.payFromTill(claim.getId(), amount);
            api.registerTradePointRentPayer(payer);
        }
    }

    @Override
    public void unregister() {
        HandlerList.unregisterAll(this);
        LoveClaimsAPI api = api();
        if (api != null && payer != null) {
            api.unregisterTradePointRentPayer(payer);
        }
        payer = null;
        manager = null;
    }

    private PointInfo info(LoveClaimsAPI api, Claim claim) {
        UUID tenant = api.hasTenant(claim) ? claim.getOwnerUuid() : null;
        Location home = claim.getHomeLocation();
        Location loc = home == null || home.getWorld() == null ? null
                : new Location(home.getWorld(), home.getBlockX() + 0.5, home.getY(), home.getBlockZ() + 0.5, home.getYaw(), home.getPitch());
        return new PointInfo(claim.getId(), claim.getName(), tenant, loc, claim.getRentalEndTime(), claim.getRentalPrice());
    }

    @Override
    public List<PointInfo> tradePoints() {
        LoveClaimsAPI api = api();
        if (api == null) return List.of();
        return api.getTradePoints().stream().map(c -> info(api, c)).toList();
    }

    @Override
    public Optional<PointInfo> point(UUID claimId) {
        LoveClaimsAPI api = api();
        if (api == null) return Optional.empty();
        return api.getClaimById(claimId).filter(api::isTradePoint).map(c -> info(api, c));
    }

    @Override
    public long renewCost(UUID claimId) {
        LoveClaimsAPI api = api();
        if (api == null) return 0L;
        return api.getClaimById(claimId).map(api::getRenewCost).orElse(0L);
    }

    @Override
    public boolean inGrace(UUID claimId) {
        LoveClaimsAPI api = api();
        if (api == null) return false;
        return api.getClaimById(claimId).map(api::isInGrace).orElse(false);
    }

    @Override
    public boolean transferTenant(UUID claimId, UUID newTenant) {
        LoveClaimsAPI api = api();
        if (api == null) return false;
        var claimOpt = api.getClaimById(claimId);
        if (claimOpt.isEmpty() || !api.isTradePoint(claimOpt.get())) return false;
        try {
            var m = api.getClass().getMethod("transferTenant", me.lovelace.loveclaims.model.Claim.class, UUID.class);
            m.invoke(api, claimOpt.get(), newTenant);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("LoveClaims transferTenant не выполнен: " + e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<UUID> pointAt(Location loc) {
        LoveClaimsAPI api = api();
        if (api == null || loc == null) return Optional.empty();
        return api.getClaimAt(loc).filter(api::isTradePoint).map(Claim::getId);
    }


    @Override
    public Optional<PointInfo> byName(String id) {
        LoveClaimsAPI api = api();
        if (api == null || id == null) return Optional.empty();
        return api.getTradePointByName(id).map(c -> info(api, c));
    }

    @Override
    public CreateResult create(String id, org.bukkit.World world, Location corner1, Location corner2, Location home, long price) {
        LoveClaimsAPI api = api();
        if (api == null) return new CreateResult(CreateStatus.FAILED, null);
        LoveClaimsAPI.CreateResult res = api.createTradePoint(id, world, corner1, corner2, home, price);
        CreateStatus status = switch (res.status()) {
            case OK -> CreateStatus.OK;
            case BAD_ID -> CreateStatus.BAD_ID;
            case ID_TAKEN -> CreateStatus.ID_TAKEN;
            case OVERLAP -> CreateStatus.OVERLAP;
            case BAD_PRICE -> CreateStatus.BAD_PRICE;
        };
        return new CreateResult(status, res.point() == null ? null : res.point().getId());
    }

    @Override
    public boolean delete(UUID claimId) {
        LoveClaimsAPI api = api();
        return api != null && api.deleteTradePoint(claimId);
    }

    @Override
    public void assign(UUID claimId, UUID tenant, long endTime) {
        LoveClaimsAPI api = api();
        if (api == null) return;
        api.getClaimById(claimId).ifPresent(c -> api.assignTradePointTenant(c, tenant, endTime));
    }

    @Override
    public void release(UUID claimId, String reason) {
        LoveClaimsAPI api = api();
        if (api == null) return;
        me.lovelace.loveclaims.api.ReleaseReason why;
        try {
            why = me.lovelace.loveclaims.api.ReleaseReason.valueOf(reason);
        } catch (IllegalArgumentException | NullPointerException e) {
            why = me.lovelace.loveclaims.api.ReleaseReason.ADMIN;
        }
        me.lovelace.loveclaims.api.ReleaseReason finalWhy = why;
        api.getClaimById(claimId).ifPresent(c -> api.releaseTradePointTenant(c, finalWhy));
    }

    @Override
    public boolean setPrice(UUID claimId, long price) {
        LoveClaimsAPI api = api();
        if (api == null) return false;
        var claim = api.getClaimById(claimId).filter(api::isTradePoint);
        claim.ifPresent(c -> api.setTradePointPrice(c, price));
        return claim.isPresent();
    }

    private static RentResult convert(me.lovelace.loveclaims.api.TradePointRentOutcome out) {
        RentStatus status = switch (out.status()) {
            case OK -> RentStatus.OK;
            case NOT_TRADE_POINT -> RentStatus.NOT_FOUND;
            case BAD_PERIODS -> RentStatus.BAD_PERIODS;
            case DENIED -> RentStatus.DENIED;
            case NO_ECONOMY -> RentStatus.NO_ECONOMY;
            case NO_FUNDS -> RentStatus.NO_FUNDS;
            case NOT_TENANT -> RentStatus.NOT_TENANT;
        };
        return new RentResult(status, out.message(), out.cost());
    }

    @Override
    public RentResult rent(org.bukkit.entity.Player player, UUID claimId, int periods) {
        LoveClaimsAPI api = api();
        if (api == null) return new RentResult(RentStatus.NOT_FOUND, null, 0L);
        return api.getClaimById(claimId).map(c -> convert(api.rentTradePoint(player, c, periods)))
                .orElse(new RentResult(RentStatus.NOT_FOUND, null, 0L));
    }

    @Override
    public RentResult extend(org.bukkit.entity.Player player, UUID claimId, int periods) {
        LoveClaimsAPI api = api();
        if (api == null) return new RentResult(RentStatus.NOT_FOUND, null, 0L);
        return api.getClaimById(claimId).map(c -> convert(api.extendTradePoint(player, c, periods)))
                .orElse(new RentResult(RentStatus.NOT_FOUND, null, 0L));
    }

    @Override
    public RentResult extendDays(org.bukkit.entity.Player player, UUID claimId, int days) {
        LoveClaimsAPI api = api();
        if (api == null) return new RentResult(RentStatus.NOT_FOUND, null, 0L);
        return api.getClaimById(claimId).map(c -> convert(api.extendTradePointDays(player, c, days)))
                .orElse(new RentResult(RentStatus.NOT_FOUND, null, 0L));
    }

    @Override
    public long dayCost(UUID claimId) {
        LoveClaimsAPI api = api();
        if (api == null) return 0L;
        return api.getClaimById(claimId).map(c -> api.getTradePointDayCost(c)).orElse(0L);
    }

    @Override
    public long rentCost(UUID claimId, int periods) {
        LoveClaimsAPI api = api();
        if (api == null) return 0L;
        return api.getClaimById(claimId).map(c -> api.getTradePointRentCost(c, periods)).orElse(0L);
    }

    @Override
    public int maxRentPeriods() {
        LoveClaimsAPI api = api();
        return api == null ? 1 : api.getTradePointMaxRentPeriods();
    }

    @Override
    public int maxExtendPeriods(UUID claimId) {
        LoveClaimsAPI api = api();
        if (api == null) return 0;
        return api.getClaimById(claimId).map(api::getTradePointMaxExtendPeriods).orElse(0);
    }

    @Override
    public long periodMillis() {
        LoveClaimsAPI api = api();
        return api == null ? 7L * 86_400_000L : api.getTradePointPeriodMillis();
    }

    @Override
    public long graceMillis() {
        LoveClaimsAPI api = api();
        return api == null ? 0L : api.getTradePointGraceMillis();
    }

    @Override
    public boolean spawnTaxer(Location location) {
        LoveClaimsAPI api = api();
        return api != null && api.spawnTaxer(location);
    }

    @Override
    public int removeTaxers() {
        LoveClaimsAPI api = api();
        return api == null ? 0 : api.removeTaxers();
    }

    @Override
    public Optional<Location> taxerLocation() {
        LoveClaimsAPI api = api();
        return api == null ? Optional.empty() : api.getTaxerLocation();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRentRequest(TradePointRentRequestEvent event) {
        if (manager == null) return;
        manager.rentDenial(event.getPlayer()).ifPresent(message -> {
            event.setCancelled(true);
            event.setDenyMessage(message);
        });
    }

    @EventHandler
    public void onRented(TradePointRentedEvent event) {
        if (manager != null) manager.onRented(event.getPlayer(), event.getPoint().getId());
    }

    @EventHandler
    public void onReleased(TradePointReleasedEvent event) {
        if (manager != null) manager.onReleased(event.getPlayer(), event.getPoint().getId(), event.getReason().name());
    }

    @EventHandler
    public void onWarning(TradePointExpiryWarningEvent event) {
        if (manager != null) manager.onExpiryWarning(event.getPlayer(), event.getPoint().getId(), event.getMillisLeft());
    }

    @EventHandler
    public void onDeleted(TradePointDeletedEvent event) {
        if (manager != null) manager.onClaimDeleted(event.getClaimId());
    }
}
