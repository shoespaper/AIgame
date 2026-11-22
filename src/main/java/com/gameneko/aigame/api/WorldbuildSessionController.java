package com.gameneko.aigame.api;

import com.gameneko.aigame.application.WorldbuildNarrationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 提供自定义世界的 AI 构建会话接口。 */
@RestController
@RequestMapping("/api/initialization/worldbuild/sessions")
public final class WorldbuildSessionController {
    private final WorldbuildNarrationService worldbuild;
    private final Map<String, WorldbuildNarrationService.Session> sessions = new ConcurrentHashMap<>();

    /** 创建世界构建控制器。
     * @param worldbuild 世界构建 AI 对话服务
     */
    public WorldbuildSessionController(WorldbuildNarrationService worldbuild) {
        this.worldbuild = worldbuild;
    }

    /** 创建世界构建会话。
     * @param request 可空的优先复用模板选择
     * @return 会话标识和第一回合
     */
    @PostMapping
    public SessionResponse create(@RequestBody(required = false) CreateRequest request) {
        String id = UUID.randomUUID().toString();
        var session = worldbuild.session(id, request == null ? null : request.templateId());
        sessions.put(id, session);
        return new SessionResponse(id, session.turn());
    }

    /** 接收世界构建回答。
     * @param id 会话标识
     * @param request 玩家回答
     * @return 下一回合
     */
    @PostMapping("/{id}/answer")
    public AnswerResponse answer(@PathVariable String id, @RequestBody AnswerRequest request) {
        if (request == null || request.answer() == null) throw new IllegalArgumentException("Answer is required");
        var session = require(id);
        var turn = worldbuild.answer(session, request.answer());
        return new AnswerResponse(turn.done(), turn);
    }

    /** 读取内存世界构建会话。
     * @param id 会话标识
     * @return 当前会话
     */
    private WorldbuildNarrationService.Session require(String id) {
        var session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("Worldbuild session not found: " + id);
        return session;
    }

    /** 世界构建会话创建响应。
     * @param id 会话标识
     * @param turn 第一回合
     */
    public record SessionResponse(String id, WorldbuildNarrationService.Turn turn) {}

    /** 世界构建创建请求。
     * @param templateId 可空的优先复用模板标识
     */
    public record CreateRequest(String templateId) {}

    /** 世界构建回答请求。
     * @param answer 玩家自然语言回答
     */
    public record AnswerRequest(String answer) {}

    /** 世界构建回答响应。
     * @param done 是否完成
     * @param turn 最新回合
     */
    public record AnswerResponse(boolean done, WorldbuildNarrationService.Turn turn) {}
}
