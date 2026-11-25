package com.gameneko.aigame.application;

import com.gameneko.aigame.application.InitializationNarrationService.Session;
import com.gameneko.aigame.application.InitializationNarrationService.Turn;
import com.gameneko.aigame.application.NarrativeApplicationService.NarratedTurn;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.GameSelection;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** 管理初始化会话的访谈、开场准备和可重试的首次存档。 */
@Service
public final class GameInitializationFlow {
    private final InitializationNarrationService narration;
    private final GameInstanceService instances;
    private final NarrativeApplicationService narratives;
    private final GameSaveService saves;
    private final Map<String, Progress> sessions = new ConcurrentHashMap<>();

    /** 装配开局确认的访谈、初始化、叙事和持久化服务。
     * @param narration 初始化访谈及答案校验服务
     * @param instances 根据确认答案构建游戏实例的服务
     * @param narratives 基于初始实例生成并结算开场的服务
     * @param saves 写入正式游戏存档的服务
     */
    public GameInitializationFlow(InitializationNarrationService narration, GameInstanceService instances,
                                  NarrativeApplicationService narratives, GameSaveService saves) {
        this.narration = narration;
        this.instances = instances;
        this.narratives = narratives;
        this.saves = saves;
    }

    /** 开启一次初始化访谈并保留该会话的开局进度。
     * @param selection 世界书与可选引擎选择
     */
    public StartedSession start(GameSelection selection) {
        String id = UUID.randomUUID().toString();
        Session interview = narration.session(id, selection);
        Turn turn = narration.open(interview);
        sessions.put(id, new Progress(interview));
        return new StartedSession(id, turn);
    }

    /** 在同一会话锁内提交访谈回答，完成的会话停止接收回答。
     * @param sessionId 初始化会话标识
     * @param input 玩家自然语言回答
     */
    public Turn answer(String sessionId, String input) {
        Progress progress = require(sessionId);
        progress.lock.lock();
        try {
            if (progress.completed) throw missingSession(sessionId);
            return narration.answer(progress.interview, input);
        } finally {
            progress.lock.unlock();
        }
    }

    /** 复用会话的游戏与开场成果，成功写档后允许重放同一确认结果。
     * @param sessionId 已满足必填字段的初始化会话标识
     */
    public NarratedTurn confirm(String sessionId) {
        Progress progress = require(sessionId);
        progress.lock.lock();
        try {
            if (progress.completed) return progress.opening;
            if (progress.opening == null) {
                if (progress.initial == null) {
                    Session interview = progress.interview;
                    InitializeWorldRequest request = narration.request(interview);
                    progress.initial = instances.initialize(progress.gameId, request, interview.selection().engineId(),
                            narration.answers(interview));
                }
                progress.opening = narratives.prepareOpening(progress.initial, "zh-CN");
                progress.initial = null;
            }
            saves.save(progress.opening.instance());
            progress.completed = true;
            progress.interview = null;
            return progress.opening;
        } finally {
            progress.lock.unlock();
        }
    }

    /** 读取已有会话的进度槽位。
     * @param sessionId 初始化会话标识
     */
    private Progress require(String sessionId) {
        Progress progress = sessions.get(sessionId);
        if (progress == null) throw missingSession(sessionId);
        return progress;
    }

    /** 创建会话不存在或已结束访谈时使用的请求错误。
     * @param sessionId 初始化会话标识
     */
    private static GameRequestException missingSession(String sessionId) {
        return new GameRequestException(GameRequestException.Code.SESSION_NOT_FOUND, "Initialization session not found: " + sessionId);
    }

    /** 首次访谈的标识和返回内容。
     * @param id 初始化会话标识
     * @param turn GM 首轮访谈回合
     */
    public record StartedSession(String id, Turn turn) {}

    /** 保存单个会话的各阶段成果，所有可变字段均由会话锁保护。 */
    private static final class Progress {
        private final ReentrantLock lock = new ReentrantLock();
        private final String gameId = "game-" + UUID.randomUUID();
        private Session interview;
        private GameInstance<?> initial;
        private NarratedTurn opening;
        private boolean completed;

        /** 为新访谈预留唯一游戏标识。
         * @param interview 已生成首轮内容的访谈会话
         */
        private Progress(Session interview) {
            this.interview = interview;
        }
    }
}
