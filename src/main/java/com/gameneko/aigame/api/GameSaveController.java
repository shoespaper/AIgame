package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.engine.GameInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP adapter for save/load of the current world state. */
@RestController
@RequestMapping("/api/games")
public final class GameSaveController {
    private final GameSaveService saveService;
    private final GameInstanceService instanceService;

    public GameSaveController(GameSaveService saveService, GameInstanceService instanceService) {
        this.saveService = saveService;
        this.instanceService = instanceService;
    }

    @PostMapping("/{gameId}/initialize")
    public GameInstanceResponse initialize(@PathVariable String gameId,
                                           @RequestBody InitializeWorldHttpRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        InitializeWorldRequest applicationRequest = new InitializeWorldRequest(
                request.worldId(), request.name(), request.description(), request.location(), request.region(),
                request.weather(), request.season(), request.time(), request.mainQuest(), request.originScene());
        var instance = instanceService.initialize(gameId, applicationRequest);
        saveService.save(instance);
        return GameInstanceResponse.from(instance);
    }

    @PostMapping("/{gameId}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable String gameId, @RequestBody SaveWorldRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        saveService.save(gameId, request.toState());
    }

    @GetMapping("/{gameId}/save")
    public WorldInitializationResponse load(@PathVariable String gameId) {
        GameInstance<?> instance = saveService.load(gameId);
        return WorldInitializationResponse.from(instance.worldState());
    }
}
