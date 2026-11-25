package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameInitializationFlow;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.InitializationNarrationService;
import com.gameneko.aigame.application.NarrativeApplicationService.NarratedTurn;
import com.gameneko.aigame.engine.GameSelection;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供由 AI 驱动的内存态初始化会话接口，正式游戏只在 finalize 后创建。 */
@RestController
@RequestMapping("/api/initialization/sessions")
public final class InitializationSessionController {
    private final GameInitializationFlow initialization;

    /** 创建初始化会话控制器。
     * @param initialization 负责访谈与开局确认的应用服务
     */
    public InitializationSessionController(GameInitializationFlow initialization) {
        this.initialization = initialization;
    }

    /** 创建会话并返回 GM 开场回合。
     * @param request 世界书和可选引擎选择
     * @return 会话标识和首个初始化回合
     */
    @PostMapping
    public SessionResponse create(@RequestBody SelectionRequest request) {
        if (request == null) throw GameRequestException.invalid("Selection is required");
        GameSelection selection = new GameSelection(request.worldId(), request.engineId());
        GameInitializationFlow.StartedSession started = initialization.start(selection);
        return new SessionResponse(started.id(), started.turn());
    }

    /** 接收玩家回答并返回下一回合。
     * @param id 初始化会话标识
     * @param request 玩家自然语言回答
     * @return 更新后的回合
     */
    @PostMapping("/{id}/answer")
    public AnswerResponse answer(@PathVariable String id, @RequestBody AnswerRequest request) {
        if (request == null) throw GameRequestException.invalid("Answer is required");
        InitializationNarrationService.Turn turn = initialization.answer(id, request.answer());
        return new AnswerResponse(turn.done(), turn);
    }

    /** 确认开局并返回同一会话对应的游戏和首段叙事。
     * @param id 已完成访谈的初始化会话标识
     */
    @PostMapping("/{id}/finalize")
    public FinalizeResponse finalizeSession(@PathVariable String id) {
        NarratedTurn turn = initialization.confirm(id);
        NarrationHttpResponse opening = NarrationHttpResponse.from(turn);
        return new FinalizeResponse(opening.game(), opening);
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
