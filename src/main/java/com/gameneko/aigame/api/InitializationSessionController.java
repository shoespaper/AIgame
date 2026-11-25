package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;

import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.GameSaveService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.InitializationNarrationService;
import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.engine.GameSelection;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 提供由 AI 驱动的内存态初始化会话接口，正式游戏只在 finalize 后创建。 */
@RestController
@RequestMapping("/api/initialization/sessions")
public final class InitializationSessionController {
    private final InitializationNarrationService narration;
    private final GameInstanceService instances;
    private final GameSaveService saves;
    private final NarrativeApplicationService narratives;
    private final Map<String, InitializationNarrationService.Session> sessions = new ConcurrentHashMap<>();

    /** 创建初始化会话控制器。
     * @param narration 负责模型问答和字段校验的初始化服务
     * @param instances 一次性创建正式游戏实例的服务
     * @param saves 保存 finalize 后游戏实例的服务
     * @param narratives 生成开局首段叙事的叙事服务
     */
    public InitializationSessionController(InitializationNarrationService narration,
                                           GameInstanceService instances,
                                           GameSaveService saves,
                                           NarrativeApplicationService narratives) {
        this.narration = narration;
        this.instances = instances;
        this.saves = saves;
        this.narratives = narratives;
    }

    /** 创建会话并返回 GM 开场回合。
     * @param request 世界书和可选引擎选择
     * @return 会话标识和首个初始化回合
     */
    @PostMapping
    public SessionResponse create(@RequestBody SelectionRequest request) {
        if (request == null) throw GameRequestException.invalid("Selection is required");
        var selection = new GameSelection(request.worldId(), request.engineId());
        String id = UUID.randomUUID().toString();
        var session = narration.session(id, selection);
        sessions.put(id, session);
        return new SessionResponse(id, narration.open(session));
    }

    /** 接收玩家回答并返回下一回合。
     * @param id 初始化会话标识
     * @param request 玩家自然语言回答
     * @return 更新后的回合
     */
    @PostMapping("/{id}/answer")
    public AnswerResponse answer(@PathVariable String id, @RequestBody AnswerRequest request) {
        if (request == null) throw GameRequestException.invalid("Answer is required");
        var session = require(id);
        var turn = narration.answer(session, request.answer());
        return new AnswerResponse(turn.done(), turn);
    }

    /** 在玩家确认后一次性创建并保存正式游戏，并附带开局背景与开场故事。 */
    /** @param id 已完成初始化的会话标识 */
    @PostMapping("/{id}/finalize")
    public FinalizeResponse finalizeSession(@PathVariable String id) {
        var session = require(id);
        if (!narration.ready(session)) throw GameRequestException.invalid("Initialization confirmation is required");
        String gameId = "game-" + UUID.randomUUID().toString().substring(0, 8);
        InitializeWorldRequest request = narration.request(session);
        var instance = instances.initialize(gameId, request, session.selection().engineId(), narration.answers(session));
        saves.save(instance);
        var opening = narratives.opening(gameId, instance.revision(), "zh-CN");
        sessions.remove(id);
        return new FinalizeResponse(GameInstanceResponse.from(opening.instance()), NarrationHttpResponse.from(opening));
    }

    /** 按会话标识读取会话并在不存在时返回明确错误。
     * @param id 会话标识
     * @return 内存中的初始化会话
     */
    private InitializationNarrationService.Session require(String id) {
        var session = sessions.get(id);
        if (session == null) throw new GameRequestException(GameRequestException.Code.SESSION_NOT_FOUND, "Initialization session not found: " + id);
        return session;
    }

    /** 创建会话请求。
     * @param worldId 世界书标识
     * @param engineId 可空的引擎标识
     */
    public record SelectionRequest(String worldId, String engineId) {}

    /** 创建会话响应。
     * @param id 会话标识
     * @param turn GM 初始化回合
     */
    public record SessionResponse(String id, InitializationNarrationService.Turn turn) {}

    /** 回答请求。
     * @param answer 玩家自然语言回答
     */
    public record AnswerRequest(String answer) {}

    /** 回答响应。
     * @param done 是否可 finalize
     * @param turn GM 初始化回合
     */
    public record AnswerResponse(boolean done, InitializationNarrationService.Turn turn) {}

    /** finalize 响应。
     * @param game 新建的游戏实例
     * @param opening 开局首段叙事与初始行动建议
     */
    public record FinalizeResponse(GameInstanceResponse game, NarrationHttpResponse opening) {}
}
