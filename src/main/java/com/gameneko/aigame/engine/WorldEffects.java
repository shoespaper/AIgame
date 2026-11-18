package com.gameneko.aigame.engine;

import java.util.List;

/** Typed effects emitted by world events and applied by application rules. */
public record WorldEffects(List<PriceModifier> priceModifiers, int threatCounterDelta,
                           int npcMoodShift, boolean travelPenalty, int outdoorSpeedModifier,
                           List<String> npcPoolAdditions, boolean shopRefresh,
                           boolean spawnThreat, int rationsBonus, int questHookChancePercent,
                           int rumorChancePercent, boolean gameOverRisk, boolean custom) {
    public WorldEffects {
        priceModifiers = priceModifiers == null ? List.of() : List.copyOf(priceModifiers);
        npcPoolAdditions = npcPoolAdditions == null ? List.of() : List.copyOf(npcPoolAdditions);
    }

    public static WorldEffects none() {
        return new WorldEffects(List.of(), 0, 0, false, 0, List.of(), false,
                false, 0, 0, 0, false, false);
    }
}
