package dev.lovelace.loveshops.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * Text of the single "Ставки" card of the caravan auction: the leader and the next few bidders in one lore instead
 * of a separate head per bidder. The viewer's own name has its own color so it is easy to find.
 */
public final class CaravanBidBoard {

    public record Bid(UUID bidder, String name, long amount) {}

    private CaravanBidBoard() {
    }

    /**
     * @param bidsNewestFirst recent bids of the current lot, newest first
     * @param shown           how many bidders after the leader are listed
     * @param money           formats an amount for display
     * @return MiniMessage lines for the lore (without the leading blank line)
     */
    public static List<String> lines(List<Bid> bidsNewestFirst, UUID viewer, int shown, LongFunction<String> money) {
        // one row per player: his best bid (the newest one on a tie)
        Map<UUID, Bid> best = new LinkedHashMap<>();
        for (Bid bid : bidsNewestFirst) {
            Bid known = best.get(bid.bidder());
            if (known == null || bid.amount() > known.amount()) {
                best.put(bid.bidder(), bid);
            }
        }
        List<Bid> ranked = new ArrayList<>(best.values());
        ranked.sort((a, b) -> Long.compare(b.amount(), a.amount()));

        List<String> out = new ArrayList<>();
        if (ranked.isEmpty()) {
            out.add("<gray>Ставок пока нет.</gray>");
            return out;
        }

        int visible = Math.min(ranked.size(), 1 + Math.max(0, shown));
        for (int i = 0; i < visible; i++) {
            out.add(row(i, ranked.get(i), viewer, money));
        }
        for (int i = visible; i < ranked.size(); i++) {
            if (ranked.get(i).bidder().equals(viewer)) {
                out.add("<dark_gray>…</dark_gray>");
                out.add(row(i, ranked.get(i), viewer, money));
                break;
            }
        }
        return out;
    }

    private static String row(int index, Bid bid, UUID viewer, LongFunction<String> money) {
        boolean me = bid.bidder().equals(viewer);
        String name = (me ? "<aqua>" : "<white>") + bid.name() + (me ? "</aqua>" : "</white>");
        String rank = index == 0 ? "<gold>🏆</gold>" : "<gray>#" + (index + 1) + "</gray>";
        String tail = index == 0 && me ? " <green>(вы лидируете)</green>" : "";
        return rank + " " + name + " <gray>—</gray> " + money.apply(bid.amount()) + tail;
    }
}
