package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.function.BooleanSupplier;

/**
 * Stub of the flea trader ("Барахольщик"). The owner asked for a skeleton only: it must never
 * appear on its own, and the real logic is written later.
 *
 * <p>Intended behaviour (not implemented yet):
 * <ul>
 *   <li>a catalog of goods from {@code market.flea.daily.pool} that is refreshed once a day;</li>
 *   <li>players list their own goods with him (limit {@code max-listings-per-player});</li>
 *   <li>a tax of {@code market.flea.tax-percent} (7% by default) is taken from player lots;</li>
 *   <li>the proceeds of sold lots are collected from the trader himself.</li>
 * </ul>
 * The {@code flea_*} tables and repository methods already exist and are left alone.
 */
public final class FleaTraderService {

    private final BooleanSupplier enabled;

    public FleaTraderService(LoveShops plugin) {
        this(() -> plugin.getMarketConfig().fleaEnabled());
    }

    /** Test seam: lets the on/off switch be supplied without a running server. */
    FleaTraderService(BooleanSupplier enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    /**
     * Would spawn the trader NPC at {@code where}. Returns {@code false} when nothing was spawned,
     * which is always the case for now.
     */
    public boolean spawn(Location where) {
        if (!isEnabled()) return false;
        // TODO: create the Citizens NPC tagged as the flea trader (as FeudalService does for the landlord).
        return false;
    }

    /** Would remove the trader NPC(s); returns how many were removed (always 0 for now). */
    public int despawn() {
        // TODO: remove every NPC tagged as the flea trader.
        return 0;
    }

    /** Would open the trader's menu for {@code player}; returns whether a menu was opened. */
    public boolean open(Player player) {
        if (!isEnabled()) return false;
        // TODO: daily catalog, the player's own lots and collecting the proceeds.
        return false;
    }

    /** Would teleport {@code player} to the trader; returns whether it happened. */
    public boolean teleportTo(Player player) {
        // TODO: find the tagged NPC and teleport the player next to it.
        return false;
    }
}
