package com.gameneko.aigame.engine;

import java.util.Objects;

/** Complete runtime envelope for one game; ruleset state is filled by the selected ruleset. */
public record GameInstance<S extends GameState>(
        String gameId,
        String worldId,
        String contentVersion,
        String rulesetId,
        String rulesetVersion,
        Lifecycle lifecycle,
        long revision,
        WorldState worldState,
        S rulesetState
)
{
    public GameInstance {
        requireText(gameId, "gameId");
        requireText(worldId, "worldId");
        requireText(contentVersion, "contentVersion");
        requireText(rulesetId, "rulesetId");
        requireText(rulesetVersion, "rulesetVersion");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(worldState, "worldState");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    public static GameInstance<GameState> initialized(String gameId, WorldState worldState) {
        return new GameInstance<>(gameId, worldState.worldId(), worldState.contentVersion(),
                "ap", "baseline", Lifecycle.INITIALIZED, worldState.revision(), worldState, null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public enum Lifecycle { INITIALIZED, ACTIVE, PAUSED, COMPLETED }
}
