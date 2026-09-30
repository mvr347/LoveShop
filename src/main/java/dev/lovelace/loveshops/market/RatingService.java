package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.MarketRepository.RatingSummary;
import dev.lovelace.loveshops.market.model.TradePoint;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Stall ratings with the abuse guards of the spec: only after real trades worth at least
 * {@code min-trade-amount}, one rating per player per stall (re-rating after the cooldown replaces
 * it), never your own stall, new accounts count for less, moderators can hide a rating.
 * Linked-account (alt) detection is NOT done here - there is no shared source for it yet.
 */
public final class RatingService {

    public enum Result { OK, SELF, NOT_TRADED, COOLDOWN, INVALID, DB_ERROR }

    private final LoveShops plugin;
    private final MarketRepository repo;

    public RatingService(LoveShops plugin, MarketRepository repo) {
        this.plugin = plugin;
        this.repo = repo;
    }

    public RatingSummary summary(TradePoint p) {
        try {
            return repo.ratingSummary(p.claimId());
        } catch (SQLException e) {
            plugin.getLogger().warning("Рейтинг точки " + p.claimId() + " не прочитан: " + e.getMessage());
            return new RatingSummary(0.0, 0);
        }
    }

    public Result rate(Player rater, TradePoint p, int stars, String rawComment) {
        UUID id = rater.getUniqueId();
        if (!p.hasOwner()) return Result.INVALID;
        if (p.isOwner(id)) return Result.SELF;
        if (stars < 1 || stars > 5) return Result.INVALID;
        String comment = sanitize(rawComment, plugin.getMarketConfig().ratingMaxComment());
        try {
            long volume = repo.tradeVolume(id, p.claimId());
            if (volume < plugin.getMarketConfig().ratingMinTrade()) return Result.NOT_TRADED;
            double weight = weightOf(rater);
            long cooldown = plugin.getMarketConfig().ratingCooldownHours() * 3_600_000L;
            return repo.inTransaction(conn -> {
                long last = repo.ratingTime(conn, p.claimId(), id);
                if (last >= 0 && System.currentTimeMillis() - last < cooldown) return Result.COOLDOWN;
                repo.upsertRating(conn, p.claimId(), id, stars, comment, volume, weight);
                return Result.OK;
            });
        } catch (SQLException e) {
            plugin.getLogger().warning("Оценка не сохранена: " + e.getMessage());
            return Result.DB_ERROR;
        }
    }

    /** A brand-new account counts for less: rating farms are made of fresh accounts. */
    private double weightOf(Player rater) {
        long firstPlayed = rater.getFirstPlayed();
        long ageDays = firstPlayed <= 0 ? 0 : (System.currentTimeMillis() - firstPlayed) / 86_400_000L;
        return ageDays < plugin.getMarketConfig().ratingNewAccountDays() ? plugin.getMarketConfig().ratingNewAccountWeight() : 1.0;
    }

    /**
     * Player text is later shown through MiniMessage/PlaceholderAPI: strip everything that could be
     * a tag, a colour code or a placeholder, control characters, and cap the length.
     */
    public static String sanitize(String raw, int maxLength) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\s+", " ").replaceAll("[<>&§%\\p{Cntrl}]", "").trim();
        if (maxLength >= 0 && s.length() > maxLength) s = s.substring(0, maxLength);
        return s;
    }
}
