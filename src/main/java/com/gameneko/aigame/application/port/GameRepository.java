package com.gameneko.aigame.application.port;

import com.gameneko.aigame.engine.GameState;

import java.util.Optional;

/** Application-owned persistence port; adapters implement it outside the core. */
public interface GameRepository {
    Optional<GameState> load(String gameId);

    void save(String gameId, GameState state);
}
