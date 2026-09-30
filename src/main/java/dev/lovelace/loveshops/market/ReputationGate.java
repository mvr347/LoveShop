package dev.lovelace.loveshops.market;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.social.BehaviorLevels;
import dev.lovelace.loveshops.LoveShops;
import dev.lovelace.loveshops.market.model.PlayerClass;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Who may do what on the player market, from LoveBehavior's levels (via LoveCore's
 * {@link BehaviorLevels}). Without LoveBehavior both scales read as neutral, so nobody is an
 * outcast or an aggressor - the gates simply never trigger.
 */
public final class ReputationGate {

    private final LoveShops plugin;

    public ReputationGate(LoveShops plugin) {
        this.plugin = plugin;
    }

    public PlayerClass classify(UUID player) {
        Optional<BehaviorLevels> levels = LoveCore.service(BehaviorLevels.class);
        if (levels.isEmpty()) return PlayerClass.NORMAL;
        int politeness = levels.get().politenessLevel(player);
        int playstyle = levels.get().playstyleLevel(player);
        return ReputationRules.classify(politeness, playstyle, plugin.getMarketConfig().thresholds());
    }

    /** Whole gate switched off in config (tests, events). */
    private boolean off() {
        return !plugin.getMarketConfig().gatesEnabled();
    }

    public boolean canRent(UUID player) {
        return off() || ReputationRules.canRent(classify(player));
    }

    public boolean canOperate(UUID player) {
        return off() || ReputationRules.canOperate(classify(player));
    }

    public boolean isTaxExempt(UUID player) {
        return plugin.getMarketConfig().taxExemptPerfect() && classify(player) == PlayerClass.PERFECT;
    }

    /** One roll: is this aggressor turned away at the stall right now? */
    public boolean aggressorRefused() {
        return ThreadLocalRandom.current().nextDouble() < plugin.getMarketConfig().aggressorRefuseChance();
    }
}
