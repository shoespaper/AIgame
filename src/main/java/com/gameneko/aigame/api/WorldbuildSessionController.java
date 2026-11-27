package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.WorldbuildNarrationService;
import com.gameneko.aigame.content.WorldbookDefinition;
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
     * @return 会话标识及初始空白状态
     */
    @PostMapping
    public SessionResponse create() {
        String id = UUID.randomUUID().toString();
        WorldbuildNarrationService.Session session = worldbuild.session(id);
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
        if (request == null || request.answer() == null) throw GameRequestException.invalid("Answer is required");
        WorldbuildNarrationService.Turn turn = worldbuild.answer(require(id), request.answer());
        return new AnswerResponse(turn.done(), turn);
    }

    /** 将当前已通过验收的草案保存为世界书。
     * @param id 会话标识
     * @param request 玩家确认保存的版本
     * @return 新世界书标识
     */
    @PostMapping("/{id}/save")
    public SaveResponse save(@PathVariable String id, @RequestBody SaveRequest request) {
        if (request == null) throw GameRequestException.invalid("草案版本不能为空");
        WorldbookDefinition worldbook = worldbuild.save(require(id), request.version());
        return new SaveResponse(worldbook.id());
    }

    /** 读取内存世界构建会话。
     * @param id 会话标识
     * @return 当前会话
     */
    private WorldbuildNarrationService.Session require(String id) {
        WorldbuildNarrationService.Session session = sessions.get(id);
        if (session == null) throw new GameRequestException(GameRequestException.Code.SESSION_NOT_FOUND, "Worldbuild session not found: " + id);
        return session;
    }

    /** 世界构建会话创建响应。
     * @param id 会话标识
     * @param turn 初始空白状态
     */
    public record SessionResponse(String id, WorldbuildNarrationService.Turn turn) {}


    /** 世界构建回答请求。
     * @param answer 玩家自然语言回答
     */
    public record AnswerRequest(String answer) {}

    /** 世界构建回答响应。
     * @param done 是否完成
     * @param turn 最新回合
     */
    public record AnswerResponse(boolean done, WorldbuildNarrationService.Turn turn) {}

    /** 保存已验收的草案版本。
     * @param version 玩家当前看到的草案版本
     */
    public record SaveRequest(long version) {}

    /** 返回可用于后续初始化的世界书标识。
     * @param worldbookId 已保存的世界书标识
     */
    public record SaveResponse(String worldbookId) {}
}
