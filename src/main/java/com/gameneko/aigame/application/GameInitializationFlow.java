package com.gameneko.aigame.application;

import com.gameneko.aigame.application.GameInitializationSchemaService.Setup;
import com.gameneko.aigame.application.GameInitializationSchemaService.Validation;
import com.gameneko.aigame.application.GameInstanceService.InitializedGame;
import com.gameneko.aigame.application.NarrativeApplicationService.NarratedTurn;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 管理开局表单版本、开场准备和可重试的首次存档。 */
@Service
public final class GameInitializationFlow {
    private final GameInitializationSchemaService schemas;
    private final GameInstanceService instances;
    private final NarrativeApplicationService narratives;
    private final GameSaveService saves;
    private final Map<String, Progress> sessions = new ConcurrentHashMap<>();

    /** 装配表单校验、初始化、叙事和持久化服务。
     * @param schemas 提供表单定义和校验结果的服务
     * @param instances 根据确认资料构建游戏实例的服务
     * @param narratives 基于初始实例生成并结算开场的服务
     * @param saves 写入正式游戏存档的服务
     */
    public GameInitializationFlow(GameInitializationSchemaService schemas, GameInstanceService instances,
                                  NarrativeApplicationService narratives, GameSaveService saves) {
        this.schemas = schemas;
        this.instances = instances;
        this.narratives = narratives;
        this.saves = saves;
    }

    /** 为当前世界与规则新建表单会话。
     * @param selection 世界书与可选引擎的选择
     */
    public StartedSession start(GameSelection selection) {
        List<InitializationField> fields = schemas.engineFields(selection);
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Progress(selection));
        return new StartedSession(id, fields);
    }

    /** 校验本轮输入并更新可确认的版本。
     * @param sessionId 初始化会话标识
     * @param profile 玩家提交的通用角色资料
     * @param engineConfig 玩家提交的引擎配置，纯叙事时应为空
     */
    public CheckedForm validate(String sessionId, Map<String, ?> profile, Map<String, ?> engineConfig) {
        Progress progress = require(sessionId);
        progress.lock.lock();
        try {
            if (progress.completed) throw GameRequestException.invalid("本次开局已完成，请重新开始创建。");
            Validation validation = schemas.validate(progress.selection, profile, engineConfig);
            progress.version++;
            progress.approved = validation.setup();
            if (progress.approved != null && !progress.approved.equals(progress.preparedSetup)) {
                progress.initial = null;
                progress.confirmed = null;
                progress.preparedSetup = null;
            }
            return new CheckedForm(progress.approved == null ? null : progress.version,
                    progress.approved, validation.profileErrors(), validation.engineErrors());
        } finally {
            progress.lock.unlock();
        }
    }

    /** 根据校验版本确认开局，并复用同一版本已准备的游戏或开场。
     * @param sessionId 初始化会话标识
     * @param version 玩家确认的服务端表单版本
     */
    public ConfirmedGame confirm(String sessionId, long version) {
        Progress progress = require(sessionId);
        progress.lock.lock();
        try {
            if (version != progress.version || progress.approved == null) {
                throw GameRequestException.invalid("角色资料已变更，请返回角色页重新校验。");
            }
            if (progress.completed) return progress.confirmed;
            if (progress.confirmed == null) {
                if (progress.initial == null) {
                    InitializeWorldRequest request = InitializeWorldRequest.defaults(progress.selection.worldId());
                    progress.initial = instances.initializeForOpening(progress.gameId, request, progress.selection.engineId(),
                            progress.approved);
                    progress.preparedSetup = progress.approved;
                }
                NarratedTurn opening = narratives.prepareOpening(progress.initial.instance(), "zh-CN");
                progress.confirmed = new ConfirmedGame(opening, progress.initial.templateBuildFailed());
                progress.initial = null;
            }
            saves.save(progress.confirmed.opening().instance());
            progress.completed = true;
            return progress.confirmed;
        } finally {
            progress.lock.unlock();
        }
    }

    /** 读取需要继续处理的表单会话。
     * @param sessionId 初始化会话标识
     */
    private Progress require(String sessionId) {
        Progress progress = sessions.get(sessionId);
        if (progress == null) {
            throw new GameRequestException(GameRequestException.Code.SESSION_NOT_FOUND, "角色表单会话已失效，请重新加载。");
        }
        return progress;
    }

    /** 返回创建会话所需的标识与字段。
     * @param id 初始化会话标识
     * @param engineFields 所选引擎声明的字段
     */
    public record StartedSession(String id, List<InitializationField> engineFields) {}

    /** 返回本轮校验结果与字段级错误。
     * @param version 校验通过的版本，失败时为空
     * @param setup 校验通过的资料，失败时为空
     * @param profileErrors 通用角色字段错误
     * @param engineErrors 引擎配置字段错误
     */
    public record CheckedForm(Long version, Setup setup, Map<String, String> profileErrors,
                              Map<String, String> engineErrors) {}

    /** 保存开局叙事及模板登记结果。
     * @param opening 结算完毕的开场回合
     * @param templateBuildFailed 模板构建失败并以无模板形式开局时为 true
     */
    public record ConfirmedGame(NarratedTurn opening, boolean templateBuildFailed) {}

    /** 保存单个表单会话的各阶段进度，所有可变字段均由会话锁保护。 */
    private static final class Progress {
        private final ReentrantLock lock = new ReentrantLock();
        private final String gameId = "game-" + UUID.randomUUID();
        private final GameSelection selection;
        private long version;
        private Setup approved;
        private Setup preparedSetup;
        private InitializedGame initial;
        private ConfirmedGame confirmed;
        private boolean completed;

        /** 固定会话的世界和规则选择。
         * @param selection 世界书及可选引擎
         */
        private Progress(GameSelection selection) {
            this.selection = selection;
        }
    }
}
