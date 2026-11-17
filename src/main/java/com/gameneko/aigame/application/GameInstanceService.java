package com.gameneko.aigame.application;

import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Creates a complete game envelope around the current initialization result. */
@Service
public final class GameInstanceService {
    private final WorldInitializationService worldInitializationService;
    private final ContentCatalog contentCatalog;

    public GameInstanceService(WorldInitializationService worldInitializationService, ContentCatalog contentCatalog) {
        this.worldInitializationService = worldInitializationService;
        this.contentCatalog = contentCatalog;
    }

    public GameInstance<?> initialize(String gameId, InitializeWorldRequest request) {
        Objects.requireNonNull(request, "request");
        var worldState = worldInitializationService.initialize(request);
        var definition = contentCatalog.world(request.worldId());
        int age = request.age() == null ? 16 : request.age();
        var runtime = new ApRulesetRuntime();
        var apState = runtime.initialize(
                new ApRulesetRuntime.ApInitializationRequest(
                        request.characterName() == null ? "旅人" : request.characterName(), age,
                        request.gender() == null ? "未定" : request.gender(), request.archetype(), request.difficulty()), definition);
        return GameInstance.initialized(gameId, worldState, runtime.id(), runtime.version(), apState);
    }
}
