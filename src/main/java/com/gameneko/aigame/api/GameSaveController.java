package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameActionService;
import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.engine.GameInstance;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP adapter for save/load and typed game commands. */
@RestController
@RequestMapping("/api/games")
public final class GameSaveController {
    private final GameSaveService saveService;
    private final GameInstanceService instanceService;
    private final GameActionService actionService;
    private final NarrativeApplicationService narrativeService;
    private final GameActionMapper actionMapper;

    public GameSaveController(GameSaveService saveService, GameInstanceService instanceService,
                              GameActionService actionService, NarrativeApplicationService narrativeService,
                              GameActionMapper actionMapper) {
        this.saveService = saveService;
        this.instanceService = instanceService;
        this.actionService = actionService;
        this.narrativeService = narrativeService;
        this.actionMapper = actionMapper;
    }

    @PostMapping("/{gameId}/initialize")
    public GameInstanceResponse initialize(@PathVariable String gameId,
                                           @RequestBody InitializeWorldHttpRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        InitializeWorldRequest applicationRequest = new InitializeWorldRequest(request.worldId(), request.name(),
                request.description(), request.location(), request.region(), request.weather(), request.season(),
                request.time(), request.mainQuest(), request.originScene(), request.characterName(), request.age(),
                request.gender(), request.archetype(), request.difficulty());
        var instance = instanceService.initialize(gameId, applicationRequest);
        saveService.save(instance);
        return GameInstanceResponse.from(instance);
    }

    @PostMapping("/{gameId}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable String gameId, @RequestBody SaveGameRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        saveService.checkpoint(gameId, request.expectedRevision());
    }

    @PostMapping("/{gameId}/actions")
    public GameInstanceResponse action(@PathVariable String gameId, @RequestBody GameActionRequest request) {
        MappedGameAction mapped = actionMapper.map(gameId, request);
        if (mapped.apAction() != null) {
            return GameInstanceResponse.from(actionService.apply(gameId, request.expectedRevision(), mapped.apAction()));
        }
        return GameInstanceResponse.from(actionService.applyWorldAction(gameId, request.expectedRevision(), mapped.worldAction()));
    }

    @PostMapping("/{gameId}/actions/narrate")
    public NarratedActionResponse narrateAction(@PathVariable String gameId,
                                                @RequestBody NarratedActionRequest request) {
        if (request == null || request.action() == null) throw new IllegalArgumentException("Action is required");
        var action = actionMapper.mapApAction(gameId, request.action());
        return NarratedActionResponse.from(narrativeService.narrateAction(gameId,
                request.action().expectedRevision(), action, request.playerInput(), request.locale()));
    }

    @GetMapping("/{gameId}")
    public GameInstanceResponse get(@PathVariable String gameId) {
        return GameInstanceResponse.from(saveService.load(gameId));
    }

    @GetMapping("/{gameId}/save")
    public WorldInitializationResponse load(@PathVariable String gameId) {
        GameInstance<?> instance = saveService.load(gameId);
        return WorldInitializationResponse.from(instance.worldState());
    }
}
