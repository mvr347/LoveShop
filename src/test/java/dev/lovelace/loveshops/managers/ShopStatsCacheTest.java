package dev.lovelace.loveshops.managers;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class ShopStatsCacheTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final ShopStatsCache.Values SOME = new ShopStatsCache.Values(2, 40, 900, 4.5, 6, 3, 70, 12);

    /** Runs queued tasks only when the test says so, like a busy database thread. */
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final AtomicLong now = new AtomicLong(1_000);
    private final AtomicInteger loads = new AtomicInteger();

    private ShopStatsCache cache(ShopStatsCache.Source source) {
        return new ShopStatsCache(source, queue::add, now::get, 60_000, Logger.getLogger("test"));
    }

    private void drain() {
        while (!queue.isEmpty()) queue.poll().run();
    }

    @Test
    void theFirstAskIsEmptyAndStartsALoad() {
        var cache = cache(p -> { loads.incrementAndGet(); return SOME; });
        assertTrue(cache.get(PLAYER).isEmpty());
        assertEquals(0, loads.get(), "the load runs on the database thread, not on the asking thread");
        drain();
        assertEquals(SOME, cache.get(PLAYER).orElseThrow());
    }

    @Test
    void askingAgainWhileLoadingDoesNotStartASecondLoad() {
        var cache = cache(p -> { loads.incrementAndGet(); return SOME; });
        cache.get(PLAYER);
        cache.get(PLAYER);
        cache.get(PLAYER);
        drain();
        assertEquals(1, loads.get());
    }

    @Test
    void aFreshEntryIsServedWithoutReloading() {
        var cache = cache(p -> { loads.incrementAndGet(); return SOME; });
        cache.get(PLAYER);
        drain();
        now.addAndGet(30_000);
        cache.get(PLAYER);
        drain();
        assertEquals(1, loads.get());
    }

    @Test
    void anOldEntryIsServedAtOnceAndRefreshedInTheBackground() {
        var changed = new ShopStatsCache.Values(3, 41, 950, 4.6, 7, 3, 71, 12);
        var cache = cache(p -> loads.incrementAndGet() == 1 ? SOME : changed);
        cache.get(PLAYER);
        drain();
        now.addAndGet(61_000);
        assertEquals(SOME, cache.get(PLAYER).orElseThrow(), "the old value is served, never a wait");
        drain();
        assertEquals(changed, cache.get(PLAYER).orElseThrow());
    }

    @Test
    void aFailedLoadLeavesTheCacheEmptyAndAllowsAnotherTry() {
        var cache = cache(p -> {
            if (loads.incrementAndGet() == 1) throw new SQLException("locked");
            return SOME;
        });
        cache.get(PLAYER);
        drain();
        assertTrue(cache.get(PLAYER).isEmpty());
        drain();
        assertEquals(SOME, cache.get(PLAYER).orElseThrow());
    }
}
