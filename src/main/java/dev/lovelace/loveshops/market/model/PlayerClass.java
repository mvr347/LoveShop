package dev.lovelace.loveshops.market.model;

/** How the market treats a player, derived from their LoveBehavior levels. */
public enum PlayerClass {
    /** Bad politeness: refused everywhere on the player market, cannot rent. */
    OUTCAST,
    /** Aggressive play style: cannot rent, trades rarely, may rob a stall. */
    AGGRESSOR,
    /** Top politeness AND a good play style: no tax. */
    PERFECT,
    NORMAL
}
