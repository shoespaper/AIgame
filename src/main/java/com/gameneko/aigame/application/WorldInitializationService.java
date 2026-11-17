package com.gameneko.aigame.application;

import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.WorldState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the initial dynamic world state without persistence or ruleset state. */
@Service
public final class WorldInitializationService {
    private final ContentCatalog contentCatalog;

    public WorldInitializationService(ContentCatalog contentCatalog) {
        this.contentCatalog = contentCatalog;
    }

    public WorldState initialize(String worldId) {
        return initialize(InitializeWorldRequest.defaults(worldId));
    }

    public WorldState initialize(String worldId, String originScene) {
        return initialize(new InitializeWorldRequest(worldId, null, null, null, null,
                null, null, null, null, originScene));
    }

    public WorldState initialize(InitializeWorldRequest request) {
        Objects.requireNonNull(request, "request");
        WorldDefinition definition = contentCatalog.world(request.worldId());
        if (request.region() != null && !definition.regionMonsterTypes().containsKey(request.region())) {
            throw new IllegalArgumentException("Unknown region: " + request.region());
        }
        return new WorldState(definition.worldId(), definition.contentVersion(), 0,
                request.name(), request.description(), request.location(), request.region(), request.mainQuest(),
                1, request.time() == null ? "\u6e05\u6668" : request.time(), request.season(), request.weather(), Map.of(),
                "Dormant", 0, definition.defaultThreatInterval(), List.of(), List.of(), List.of(),
                0, false, 0, List.of(), Map.of(), request.originScene() == null ? "" : request.originScene());
    }
}
