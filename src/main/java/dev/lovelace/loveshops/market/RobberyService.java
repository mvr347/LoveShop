package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.economy.LoveEconomy;
import dev.lovelace.lovecore.api.social.ReputationOracle;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository.PointState;
import dev.lovelace.loveshops.market.MarketRepository.RobberyState;
import dev.lovelace.loveshops.market.model.CloseReason;
import dev.lovelace.loveshops.market.model.GuardState;
import dev.lovelace.loveshops.market.model.ListingType;
import dev.lovelace.loveshops.market.model.StallListing;
import dev.lovelace.loveshops.market.model.TradePoint;
import dev.lovelace.loveshops.utils.ItemStackConverter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What happens when an AGGRESSOR keeps clicking a stall trader.
 * <ul>
 *   <li>No guard: every click of the day makes a robbery a little more likely; after a day's
 *       budget of clicks without one it will not happen today. Several aggressors at once combine
 *       with diminishing returns (never 100%).</li>
 *   <li>With a guard: no robbery is possible; keep pestering the trader and you are thrown out of
 *       the market for a while.</li>
 * </ul>
 * A robbery takes money OR goods, gives them to the robber, and shuts the shop until its owner
 * opens it again. All state is in the database, so logging out resets nothing.
 */
public final class RobberyService {

    public enum Outcome { PROCEED, REFUSED, LOCKED, HOSTILE, BANNED, KICKED, ROBBED, NOTHING_TO_TAKE }

    /** {@code stage}: 0 calm, 1 annoyed, 2 warning - which line the trader says when refusing. */
    public record Click(Outcome outcome, int stage, boolean guarded) {}

    private record Decision(Outcome outcome, int stage) {}

    private record Loot(long coins, List<Taken> items, long logId) {}

    private record Taken(long listingId, ItemStack template, int amount) {}

    private final LoveShops plugin;
    private final MarketRepository repo;
    private final TradePointManager manager;
    private final ReputationGate gate;
    private final ZoneId zone = ZoneId.systemDefault();

    public RobberyService(LoveShops plugin, MarketRepository repo, TradePointManager manager, ReputationGate gate) {
        this.plugin = plugin;
        this.repo = repo;
        this.manager = manager;
        this.gate = gate;
    }

    private static boolean guardActive(TradePoint p) {
        return p.guardState() == GuardState.ACTIVE;
    }

    public String currentDayKey() {
        return RobberyMath.dayKey(System.currentTimeMillis(), plugin.getMarketConfig().robberyDayResetHour(), zone);
    }

    // ------------------------------------------------------------------ clicks

    public Click onAggressorClick(Player player, TradePoint p) {
        boolean guarded = guardActive(p);
        if (!plugin.getMarketConfig().robberyEnabled()) {
            return new Click(gate.aggressorRefused() ? Outcome.REFUSED : Outcome.PROCEED, 0, guarded);
        }
        UUID id = player.getUniqueId();
        var cfg = plugin.getMarketConfig();
        long now = System.currentTimeMillis();
        String day = currentDayKey();
        long dayStart = RobberyMath.dayStartMillis(day, cfg.robberyDayResetHour(), zone);

        Decision decision;
        try {
            decision = repo.inTransaction(conn -> {
                RobberyState st = repo.robberyState(conn, id, p.claimId());
                if (!day.equals(st.dayKey())) {
                    st = new RobberyState(id, p.claimId(), st.robberyUntil(), st.hostileUntil(), st.harassment(),
                            st.harassmentSince(), st.bannedUntil(), day, 0, 0.0, st.lastClickAt(), false);
                }
                if (st.bannedUntil() > now) return new Decision(Outcome.BANNED, 0);

                if (guarded) {
                    long window = cfg.guardHarassmentWindowMinutes() * 60_000L;
                    int h = st.harassment();
                    long since = st.harassmentSince();
                    if (now - since > window) {
                        h = 0;
                        since = now;
                    }
                    h++;
                    int limit = cfg.guardHarassmentLimit();
                    if (h >= limit) {
                        repo.saveRobberyState(conn, new RobberyState(id, p.claimId(), st.robberyUntil(), st.hostileUntil(), 0, 0,
                                now + cfg.guardHarassmentBanMinutes() * 60_000L, day, st.clicksToday(), st.lastChance(), now, st.dayLocked()));
                        return new Decision(Outcome.KICKED, 2);
                    }
                    repo.saveRobberyState(conn, new RobberyState(id, p.claimId(), st.robberyUntil(), st.hostileUntil(), h, since,
                            st.bannedUntil(), day, st.clicksToday(), st.lastChance(), now, st.dayLocked()));
                    int stage = RobberyMath.stage(RobberyMath.patience(h, limit));
                    return new Decision(gate.aggressorRefused() ? Outcome.REFUSED : Outcome.PROCEED, stage);
                }

                if (st.hostileUntil() > now) return new Decision(Outcome.HOSTILE, 0);
                if (st.dayLocked()) return new Decision(Outcome.LOCKED, 2);

                int budget = cfg.robberyAttemptsPerDay();
                int k = st.clicksToday() + 1;
                if (k > budget) {
                    repo.saveRobberyState(conn, new RobberyState(id, p.claimId(), st.robberyUntil(), st.hostileUntil(), st.harassment(),
                            st.harassmentSince(), st.bannedUntil(), day, st.clicksToday(), st.lastChance(), now, true));
                    return new Decision(Outcome.LOCKED, 2);
                }
                double own = RobberyMath.chance(k, cfg.robberyBaseChance(), cfg.robberyStep(), cfg.robberyChanceCap());
                List<Double> chances = new ArrayList<>(repo.coAttackChances(conn, p.claimId(), id, day,
                        now - cfg.robberyCoAttackWindowMinutes() * 60_000L));
                chances.add(own);
                double combined = RobberyMath.combined(chances, cfg.robberyMaxCombined());
                repo.saveRobberyState(conn, new RobberyState(id, p.claimId(), st.robberyUntil(), st.hostileUntil(), st.harassment(),
                        st.harassmentSince(), st.bannedUntil(), day, k, own, now, false));

                boolean mayRob = st.robberyUntil() <= now
                        && repo.robberiesSince(conn, id, null, dayStart) < cfg.robberyMaxPerPlayerPerDay()
                        && repo.robberiesSince(conn, null, p.claimId(), dayStart) < cfg.robberyMaxPerPointPerDay();
                if (mayRob && ThreadLocalRandom.current().nextDouble() < combined) {
                    return new Decision(Outcome.ROBBED, 2);
                }
                int stage = RobberyMath.stage(RobberyMath.patience(k, budget));
                return new Decision(gate.aggressorRefused() ? Outcome.REFUSED : Outcome.PROCEED, stage);
            });
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().warning("Клик агрессора не обработан: " + e.getMessage());
            return new Click(Outcome.REFUSED, 0, guarded);
        }

        if (decision.outcome() == Outcome.ROBBED) {
            return new Click(rob(player, p), 2, false);
        }
        if (decision.outcome() == Outcome.KICKED) kick(player);
        return new Click(decision.outcome(), decision.stage(), guarded);
    }

    /** Sends the player to the market exit; the ban itself is already in the database. */
    private void kick(Player player) {
        Location exit = plugin.getMarketConfig().exitLocation();
        if (exit != null) player.teleport(exit);
    }

    // ------------------------------------------------------------------ the robbery

    /**
     * Takes money or goods from the stall, hands them to the robber and shuts the shop. One
     * transaction covers the stall's change, the log, the owner's notice and the robber's cooldown;
     * the loot reaches the robber only after it is committed.
     */
    private Outcome rob(Player robber, TradePoint p) {
        LoveEconomy eco = plugin.getEconomy().orElse(null);
        var cfg = plugin.getMarketConfig();
        UUID owner = p.ownerUuid();
        UUID robberId = robber.getUniqueId();
        long now = System.currentTimeMillis();
        boolean ownerOnline = owner != null && Bukkit.getPlayer(owner) != null;

        Loot loot;
        try {
            loot = repo.inTransaction(conn -> {
                PointState ps = repo.pointState(conn, p.claimId());
                if (ps == null || ps.owner() == null || !ps.open()) return null;

                List<StallListing> stocked = new ArrayList<>();
                for (StallListing l : repo.listings(conn, p.claimId())) {
                    if (l.type() == ListingType.SELL && l.stock() > 0) stocked.add(l);
                }
                long coinsAvailable = ps.till();
                boolean wantCoins = ThreadLocalRandom.current().nextDouble() < cfg.robberyCoinsChance();
                boolean takeCoins = wantCoins ? coinsAvailable > 0 : stocked.isEmpty() && coinsAvailable > 0;
                boolean takeItems = !takeCoins && !stocked.isEmpty();
                if (!takeCoins && !takeItems) return new Loot(0, List.of(), -1L);

                long coins = 0;
                List<Taken> items = new ArrayList<>();
                if (takeCoins) {
                    coins = RobberyMath.coinLoot(coinsAvailable, cfg.robberyMaxTillPercent());
                    repo.addTill(conn, p.claimId(), -coins, 0, 0);
                } else {
                    Collections.shuffle(stocked);
                    int shelves = RobberyMath.itemShelvesToTake(stocked.size(), cfg.robberyMaxStockPercent(), cfg.robberyMaxItems());
                    for (int i = 0; i < shelves; i++) {
                        StallListing l = stocked.get(i);
                        ItemStack template = l.template();
                        int amount = Math.min(l.stock(), Math.max(1, template.getMaxStackSize()));
                        repo.addListingStock(conn, l.id(), -amount);
                        items.add(new Taken(l.id(), template, amount));
                    }
                }
                repo.closePoint(conn, p.claimId(), CloseReason.ROBBERY);
                long logId = repo.insertRobberyLog(conn, robberId, p.claimId(), owner, coins, itemsJson(items));

                RobberyState st = repo.robberyState(conn, robberId, p.claimId());
                repo.saveRobberyState(conn, new RobberyState(robberId, p.claimId(),
                        now + cfg.robberyCooldownHours() * 3_600_000L, now + cfg.robberyHostilityMinutes() * 60_000L,
                        st.harassment(), st.harassmentSince(), st.bannedUntil(), st.dayKey(), st.clicksToday(),
                        st.lastChance(), st.lastClickAt(), st.dayLocked()));
                if (!ownerOnline) {
                    repo.addNotice(conn, owner, plugin.getMarketMessages().raw("notice-robbed", "player", robber.getName()));
                }
                return new Loot(coins, items, logId);
            });
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().severe("Ограбление точки " + p.claimId() + " не записано: " + e.getMessage());
            return Outcome.REFUSED;
        }
        if (loot == null) return Outcome.REFUSED; // the shop was closed meanwhile

        if (loot.logId() < 0) {
            // Nothing to take: the attempt is used up, the shop stays open.
            return Outcome.NOTHING_TO_TAKE;
        }

        // In memory: the stall is closed and lighter.
        p.open(false);
        p.closeReason(CloseReason.ROBBERY);
        p.tillCoins(Math.max(0, p.tillCoins() - loot.coins()));
        manager.refreshCounters(p.claimId());
        manager.refreshViewers(p.claimId());

        // The loot goes to the robber; what does not fit falls at their feet - it is loot, not a delivery.
        if (loot.coins() > 0 && eco != null) {
            try {
                eco.give(robber, loot.coins());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Добыча " + loot.coins() + " грабителю " + robber.getName()
                        + " не выдана (ограбление #" + loot.logId() + "): " + e.getMessage());
            }
        }
        for (Taken t : loot.items()) {
            int left = ItemTransfer.give(robber, t.template(), t.amount());
            if (left > 0) {
                ItemStack drop = t.template();
                drop.setAmount(left);
                robber.getWorld().dropItemNaturally(robber.getLocation(), drop);
            }
        }

        var cfgPenalty = cfg.robberyReputationPenalty();
        if (cfgPenalty > 0) {
            LoveCore.service(ReputationOracle.class).ifPresent(o -> o.modify(robberId, -cfgPenalty));
        }
        Player ownerPlayer = owner == null ? null : Bukkit.getPlayer(owner);
        if (ownerPlayer != null) {
            plugin.getMarketMessages().send(ownerPlayer, "shop-robbed", "player", robber.getName());
        }
        plugin.getLogger().info("Ограбление #" + loot.logId() + ": " + robber.getName() + " -> точка " + p.claimId()
                + " (монет " + loot.coins() + ", стопок " + loot.items().size() + ")");
        return Outcome.ROBBED;
    }

    /** {@code [{"item":"<base64>","amount":n}, ...]} - enough for an administrator to restore the goods. */
    private static String itemsJson(List<Taken> items) {
        if (items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            Taken t = items.get(i);
            ItemStack one = t.template().clone();
            one.setAmount(1);
            sb.append("{\"item\":\"").append(ItemStackConverter.itemStackToBase64(one))
              .append("\",\"amount\":").append(t.amount()).append('}');
        }
        return sb.append(']').toString();
    }
}
