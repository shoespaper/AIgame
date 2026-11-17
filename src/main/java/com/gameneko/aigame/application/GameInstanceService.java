package com.gameneko.aigame.application;

import com.gameneko.aigame.engine.GameInstance;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Creates a complete game envelope around the current initialization result. */
@Service
public final class GameInstanceService {
    private final WorldInitializationService worldInitializationService;

    public GameInstanceService(WorldInitializationService worldInitializationService) {
        this.worldInitializationService = worldInitializationService;
    }

    public GameInstance<?> initialize(String gameId, InitializeWorldRequest request) {
        Objects.requireNonNull(request, "request");
        return GameInstance.initialized(gameId, worldInitializationService.initialize(request));
    }
}
