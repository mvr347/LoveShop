package dev.lovelace.loveshops.gui;

import dev.lovelace.loveshops.gui.CaravanBidBoard.Bid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CaravanBidBoardTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), ME = UUID.randomUUID();

    private static List<String> lines(List<Bid> bids, UUID viewer, int shown) {
        return CaravanBidBoard.lines(bids, viewer, shown, v -> v + "c");
    }

    @Test
    void emptyShowsPlaceholder() {
        assertEquals(1, lines(List.of(), ME, 3).size());
    }

    @Test
    void leaderFirstAndOneRowPerPlayer() {
        // newest first: A raised twice
        var out = lines(List.of(new Bid(A, "A", 300), new Bid(B, "B", 200), new Bid(A, "A", 100)), ME, 3);
        assertEquals(2, out.size());
        assertTrue(out.get(0).contains("🏆") && out.get(0).contains("300c"));
        assertTrue(out.get(1).contains("#2") && out.get(1).contains("200c"));
    }

    @Test
    void viewerNameHasOwnColorAndLeaderMarker() {
        var out = lines(List.of(new Bid(ME, "Me", 300), new Bid(A, "A", 200)), ME, 3);
        assertTrue(out.get(0).contains("<aqua>Me</aqua>") && out.get(0).contains("вы лидируете"));
        assertTrue(out.get(1).contains("<white>A</white>"));
    }

    @Test
    void viewerOutsideTopIsAppendedWithHisRank() {
        var bids = List.of(new Bid(A, "A", 500), new Bid(B, "B", 400), new Bid(C, "C", 300), new Bid(ME, "Me", 100));
        var out = lines(bids, ME, 2);
        assertEquals(5, out.size());
        assertTrue(out.get(4).contains("#4") && out.get(4).contains("<aqua>Me</aqua>"));
    }
}
