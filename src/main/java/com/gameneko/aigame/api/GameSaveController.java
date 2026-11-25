package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;

import com.gameneko.aigame.application.GameActionService;
import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.logging.DiagnosticLog;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Thin HTTP adapter for save/load and typed game commands. */
@RestController
@RequestMapping("/api/games")
public final class GameSaveController {
    private final GameSaveService saveService;
    private final GameActionService actionService;
    private final GameActionMapper actionMapper;

    public GameSaveController(GameSaveService saveService, GameActionService actionService,
                              GameActionMapper actionMapper) {
        this.saveService = saveService;
        this.actionService = actionService;
        this.actionMapper = actionMapper;
    }

    /** 按客户端提交的修订号保存当前权威状态。
     * @param gameId 游戏存档标识
     * @param request 包含预期修订号的保存请求
     */
    @PostMapping("/{gameId}/save")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable String gameId, @RequestBody SaveGameRequest request) {
        if (request == null) throw GameRequestException.invalid("Request body is required");
        saveService.checkpoint(gameId, request.expectedRevision());
    }

    /** 执行引擎中立动作并记录成功提交的权威版本。
     * @param gameId 游戏存档标识
     * @param request 动作与预期修订号
     */
    @PostMapping("/{gameId}/actions")
    public GameInstanceResponse action(@PathVariable String gameId, @RequestBody GameActionRequest request) {
        MappedGameAction mapped = actionMapper.map(gameId, request);
        GameInstance<?> instance = mapped.intent() == null
                ? actionService.applyWorldAction(gameId, request.expectedRevision(), mapped.worldAction())
                : actionService.applyIntent(gameId, request.expectedRevision(), mapped.intent()).instance();
        DiagnosticLog.actionCommitted(new DiagnosticLog.Action(gameId, instance.rulesetId(), request.type(),
                request.expectedRevision(), instance.revision()));
        return GameInstanceResponse.from(instance);
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
