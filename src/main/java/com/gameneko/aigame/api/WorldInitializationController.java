package com.gameneko.aigame.api;

import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.WorldInitializationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP adapter for the in-memory world initialization use case. */
@RestController
@RequestMapping("/api/worlds")
public final class WorldInitializationController {
    private final WorldInitializationService initializationService;

    public WorldInitializationController(WorldInitializationService initializationService) {
        this.initializationService = initializationService;
    }

    @PostMapping("/initialize")
    @ResponseStatus(HttpStatus.OK)
    public WorldInitializationResponse initialize(@RequestBody InitializeWorldHttpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        InitializeWorldRequest applicationRequest = new InitializeWorldRequest(
                request.worldId(), request.name(), request.description(), request.location(), request.region(),
                request.weather(), request.season(), request.time(), request.mainQuest(), request.originScene(),
                request.characterName(), request.age(), request.gender(), request.archetype(), request.difficulty());
        return WorldInitializationResponse.from(initializationService.initialize(applicationRequest));
    }
}
