package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameActionService;
import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.GameSaveService;
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

import java.util.Map;
import java.util.List;
import com.gameneko.aigame.engine.InitializationValue;

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

    @PostMapping("/{gameId}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable String gameId, @RequestBody SaveGameRequest request) {
        if (request == null) throw new IllegalArgumentException("Request body is required");
        saveService.checkpoint(gameId, request.expectedRevision());
    }

    @PostMapping("/{gameId}/actions")
    public GameInstanceResponse action(@PathVariable String gameId, @RequestBody GameActionRequest request) {
        MappedGameAction mapped = actionMapper.map(gameId, request);
        if (mapped.intent() != null) return GameInstanceResponse.from(actionService.applyIntent(gameId, request.expectedRevision(), mapped.intent()).instance());
        return GameInstanceResponse.from(actionService.applyWorldAction(gameId, request.expectedRevision(), mapped.worldAction()));
    }

    @PostMapping("/{gameId}/actions/narrate")
    public NarratedActionResponse narrateAction(@PathVariable String gameId,
                                                @RequestBody NarratedActionRequest request) {
        if (request == null || request.action() == null) throw new IllegalArgumentException("Action is required");
        var mapped = actionMapper.map(gameId, request.action());
        if (mapped.intent() == null) throw new IllegalArgumentException("World actions cannot be narrated as engine intents");
        return NarratedActionResponse.from(narrativeService.narrateAction(gameId,
                request.action().expectedRevision(), mapped.intent(), request.playerInput(), request.locale()));
    }

    @GetMapping("/{gameId}")
    public GameInstanceResponse get(@PathVariable String gameId) {
        return GameInstanceResponse.from(saveService.load(gameId));
    }

    /** 返回全部存档的轻量元数据，避免列表页加载完整引擎状态。 */
    @GetMapping
    public List<SaveMetadata> list() {
        return saveService.list().stream().map(instance -> new SaveMetadata(instance.gameId(), instance.worldId(),
                instance.rulesetId(), instance.worldState().day(), instance.revision(), "")).toList();
    }

    @GetMapping("/{gameId}/save")
    public WorldInitializationResponse load(@PathVariable String gameId) {
        GameInstance<?> instance = saveService.load(gameId);
        return WorldInitializationResponse.from(instance.worldState());
    }

    /** 存档列表所需的稳定元数据。 */
    public record SaveMetadata(String gameId, String worldId, String engineId, long day, long revision, String updatedAt) {}
}
