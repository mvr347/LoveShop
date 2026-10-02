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
        return new PointInfo(claim.getId(), claim.getName(), tenant, loc, claim.getRentalEndTime());
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
