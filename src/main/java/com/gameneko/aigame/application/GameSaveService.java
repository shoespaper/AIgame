package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.WorldState;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Application boundary for saving and loading the current game state. */
@Service
public final class GameSaveService {
    private final GameRepository repository;
    public GameSaveService(GameRepository repository) { this.repository = repository; }
    public void save(String gameId, WorldState state) {
        repository.save(GameInstance.initialized(gameId, Objects.requireNonNull(state, "state")));
    }
    public void save(GameInstance<?> instance) {
        repository.save(Objects.requireNonNull(instance, "instance"));
    }
    public GameInstance<?> load(String gameId) {
        return repository.load(gameId).orElseThrow(() -> new IllegalArgumentException("Save not found: " + gameId));
    }
}
