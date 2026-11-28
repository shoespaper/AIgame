package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameInitializationFlow;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.NarrativeApplicationService.NarratedTurn;
import com.gameneko.aigame.engine.CharacterProfile;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** 提供角色表单加载、校验和开局确认接口。 */
@RestController
@RequestMapping("/api/initialization/sessions")
public final class InitializationSessionController {
    private final GameInitializationFlow initialization;

    /** 创建开局表单控制器。
     * @param initialization 管理表单会话和开局确认的服务
     */
    public InitializationSessionController(GameInitializationFlow initialization) {
        this.initialization = initialization;
    }

    /** 根据选定的世界和规则加载角色表单。
     * @param selection 世界书及可选引擎选择
     */
    @PostMapping
    public SessionResponse create(@RequestBody GameSelection selection) {
        GameInitializationFlow.StartedSession started = initialization.start(selection);
        return new SessionResponse(started.id(), CharacterProfile.SCHEMA.fields(), started.engineFields());
    }

    /** 校验玩家填写的通用资料及引擎配置。
     * @param id 表单会话标识
     * @param request 当前表单提交
     */
    @PostMapping("/{id}/validate")
    public GameInitializationFlow.CheckedForm validate(@PathVariable String id, @RequestBody FormRequest request) {
        return initialization.validate(id, request.profile(), request.engineConfig());
    }

    /** 确认已校验的版本并返回开场。
     * @param id 表单会话标识
     * @param request 玩家在确认页核对的版本
     */
    @PostMapping("/{id}/finalize")
    public FinalizeResponse finalizeSession(@PathVariable String id, @RequestBody ConfirmRequest request) {
        if (request == null || request.version() <= 0) throw GameRequestException.invalid("请先校验角色表单。");
        return finalizeResponse(initialization.confirm(id, request.version()));
    }

    /** 推送模板与开场进度，并在存档后返回可游玩的结果。
     * @param id 表单会话标识
     * @param request 玩家确认的已校验版本
     */
    @PostMapping(path = "/{id}/finalize/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamFinalize(@PathVariable String id, @RequestBody ConfirmRequest request) {
        if (request == null || request.version() <= 0) throw GameRequestException.invalid("请先校验角色表单。");
        return CreationStream.start("/api/initialization/sessions/{id}/finalize/stream",
                progress -> finalizeResponse(initialization.confirm(id, request.version(), progress)));
    }

    /** 转换已写入存档的开场。
     * @param turn 应用层返回的开场回合
     */
    private static FinalizeResponse finalizeResponse(NarratedTurn turn) {
        NarrationHttpResponse opening = NarrationHttpResponse.from(turn);
        return new FinalizeResponse(opening.game(), opening);
    }

    /** 返回会话标识以及两组字段定义。
     * @param id 表单会话标识
     * @param profileFields 通用角色字段
     * @param engineFields 引擎专用字段
     */
    public record SessionResponse(String id, List<InitializationField> profileFields,
                                  List<InitializationField> engineFields) {}

    /** 接收表单输入，服务端根据字段定义验证类型及范围。
     * @param profile 通用角色字段值
     * @param engineConfig 引擎专用字段值，纯叙事时应为空
     */
    public record FormRequest(Map<String, Object> profile, Map<String, Object> engineConfig) {}

    /** 指定待确认的已校验版本。
     * @param version 表单版本
     */
    public record ConfirmRequest(long version) {}

    /** 返回可进入游玩的存档及开场。
     * @param game 新建的游戏实例
     * @param opening 开场叙事与初始行动建议
     */
    public record FinalizeResponse(GameInstanceResponse game, NarrationHttpResponse opening) {}
}
