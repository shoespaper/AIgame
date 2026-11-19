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
        if (rulesetState != null) {
            requireText(rulesetId, "rulesetId");
            requireText(rulesetVersion, "rulesetVersion");
        } else if (rulesetId != null || rulesetVersion != null) {
            throw new IllegalArgumentException("An engine id and version require engine state");
        }
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(worldState, "worldState");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    public static GameInstance<GameState> initialized(String gameId, WorldState worldState) {
        return narrativeOnly(gameId, worldState);
    }

    /** Creates a game with a world book but no selected game engine. */
    public static GameInstance<GameState> narrativeOnly(String gameId, WorldState worldState) {
        return new GameInstance<>(gameId, worldState.worldId(), worldState.contentVersion(),
                null, null, Lifecycle.INITIALIZED, worldState.revision(), worldState, null);
    }

    public static <S extends GameState> GameInstance<S> initialized(String gameId, WorldState worldState,
                                                                     String rulesetId, String rulesetVersion,
                                                                     S rulesetState) {
        return new GameInstance<>(gameId, worldState.worldId(), worldState.contentVersion(), rulesetId,
                rulesetVersion, Lifecycle.INITIALIZED, worldState.revision(), worldState, rulesetState);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public enum Lifecycle { INITIALIZED, ACTIVE, PAUSED, COMPLETED }
}
