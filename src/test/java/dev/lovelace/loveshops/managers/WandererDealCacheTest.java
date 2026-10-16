package dev.lovelace.loveshops.managers;

import dev.lovelace.loveshops.models.WandererDeal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WandererDealCacheTest {
    private static WandererDeal deal(UUID u, String status, long ready, long expires) {
        return new WandererDeal(1, u, status, 0, ready, expires, List.of(), null);
    }

    @Test
    void untrackedPlayersAreNeverStored() {
        WandererDealCache c = new WandererDealCache();
        UUID u = UUID.randomUUID();
        c.update(u, Optional.of(deal(u, "WAITING", 10, 100)));
        assertEquals("NONE", c.status(u, 50));
        assertTrue(c.get(u).isEmpty());
    }

    @Test
    void statusPromotesWithTimeWithoutDb() {
        WandererDealCache c = new WandererDealCache();
        UUID u = UUID.randomUUID();
        c.track(u);
        c.update(u, Optional.of(deal(u, "WAITING", 10, 100)));
        assertEquals("WAITING", c.status(u, 5));
        assertEquals("READY", c.status(u, 20));
        assertEquals("EXPIRED", c.status(u, 101));
        c.untrack(u);
        assertEquals("NONE", c.status(u, 20));
    }
}
