package com.gameneko.aigame.api;

import com.gameneko.aigame.engine.GameInstance;

/** Stable API projection of a complete game envelope. */
public record GameInstanceResponse(
        String gameId,
        String worldId,
        String contentVersion,
        String rulesetId,
        String rulesetVersion,
        GameInstance.Lifecycle lifecycle,
        long revision,
        WorldInitializationResponse world,
        Object rulesetState
) {
    public static GameInstanceResponse from(GameInstance<?> instance) {
        return new GameInstanceResponse(instance.gameId(), instance.worldId(), instance.contentVersion(),
                instance.rulesetId(), instance.rulesetVersion(), instance.lifecycle(), instance.revision(),
                WorldInitializationResponse.from(instance.worldState()), instance.rulesetState());
    }
}
