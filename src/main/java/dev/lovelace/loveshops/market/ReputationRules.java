package dev.lovelace.loveshops.market;

import dev.lovelace.loveshops.market.model.PlayerClass;

/**
 * The rules that turn LoveBehavior's two 0..6 scales into a {@link PlayerClass}. Pure, so the
 * thresholds are unit-tested instead of trusted.
 *
 * <p>Politeness: 0 = terrible ... 6 = maximum. Play style: 0 = aggressive ... 6 = kind.
 * Outcast is checked first: an impolite player is an outcast whatever their play style.</p>
 */
public final class ReputationRules {

    /** Limits, all inclusive. */
    public record Thresholds(int outcastMaxPoliteness, int aggressiveMaxPlaystyle,
                             int perfectMinPoliteness, int goodMinPlaystyle) {}

    private ReputationRules() {}

    public static PlayerClass classify(int politeness, int playstyle, Thresholds t) {
        if (politeness <= t.outcastMaxPoliteness()) return PlayerClass.OUTCAST;
        if (playstyle <= t.aggressiveMaxPlaystyle()) return PlayerClass.AGGRESSOR;
        if (politeness >= t.perfectMinPoliteness() && playstyle >= t.goodMinPlaystyle()) return PlayerClass.PERFECT;
        return PlayerClass.NORMAL;
    }

    /** May this class rent a trade point? */
    public static boolean canRent(PlayerClass c) {
        return c == PlayerClass.NORMAL || c == PlayerClass.PERFECT;
    }

    /** May this class own an open shop? Same rule as renting: a shop must not stay open under a banned class. */
    public static boolean canOperate(PlayerClass c) {
        return canRent(c);
    }
}
