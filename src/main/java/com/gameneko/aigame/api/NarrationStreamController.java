package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.application.NarrativeApplicationService.AttemptFailure;
import com.gameneko.aigame.application.NarrativeApplicationService.NarratedTurn;
import com.gameneko.aigame.logging.DiagnosticLog;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.narrative.NarrativeRequest.ConversationMessage;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 流式发送自由文本和直接行动的回合进度，重连使用相同标识并共享响应。 */
@RestController
@RequestMapping("/api/games/{gameId}/narration/turns")
public final class NarrationStreamController {
    private static final String ROUTE = "/api/games/{gameId}/narration/turns/{turnId}";
    private static final int MAX_CACHED_TURNS = 128;
    /** 按每次调用两分钟的流连接预算覆盖整个回合。 */
    private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(2)
            .multipliedBy(NarrativeApplicationService.MAX_MODEL_ATTEMPTS).toMillis();
    private final Map<TurnKey, TurnSession> sessions = new LinkedHashMap<>();
    private final NarrativeApplicationService narratives;

    /** 注入负责重试与结算的应用服务。
     * @param narratives 执行叙事回合并读取回执的服务
     */
    public NarrationStreamController(NarrativeApplicationService narratives) {
        this.narratives = narratives;
    }

    /** 启动一个叙事回合，或重新附着到相同回合。
     * @param gameId 游戏标识
     * @param turnId 客户端为本轮提交生成的 UUID
     * @param request 原始请求及近期对话
     */
    @PostMapping(path = "/{turnId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter narrate(@PathVariable String gameId, @PathVariable UUID turnId,
                              @RequestBody NarrationHttpRequest request) {
        if (request == null || (request.choiceId() == null && (request.playerInput() == null
                || request.playerInput().isBlank() || request.playerInput().length() > 2000))) {
            throw GameRequestException.invalid("请输入有效的自由叙事文本。");
        }
        TurnKey key = new TurnKey(gameId, turnId);
        SseEmitter stream = new SseEmitter(STREAM_TIMEOUT_MS);
        stream.onTimeout(stream::complete);
        boolean owner;
        TurnSession session;
        synchronized (sessions) {
            session = sessions.get(key);
            owner = session == null;
            if (owner) {
                if (sessions.size() >= MAX_CACHED_TURNS) {
                    sessions.values().removeIf(value -> value.result().isDone());
                }
                if (sessions.size() >= MAX_CACHED_TURNS) throw GameRequestException.invalid("当前有较多回合正在处理，请稍后再试。");
                session = new TurnSession(request, new CompletableFuture<>());
                sessions.put(key, session);
            } else if (!session.request().equals(request)) {
                throw GameRequestException.invalid("此回合标识已用于另一条请求。");
            }
        }
        send(stream, new StreamEvent("started", null, null, null, null));
        TurnSession attached = session;
        if (owner) {
            String requestId = MDC.get(RequestLoggingFilter.REQUEST_ID);
            Thread.startVirtualThread(() -> DiagnosticLog.withAsyncModelCalls(() -> execute(key, attached, stream, requestId)));
        } else {
            attached.result().thenAccept(event -> finish(stream, event));
        }
        return stream;
    }

    /** 执行唯一的结算工作，并为连接断开后的恢复保留结果。
     * @param key 当前游戏与回合标识
     * @param session 包含原始请求及完成结果的会话
     * @param stream 原始连接，断开后仍完成该回合
     * @param requestId 用于关联模型日志的 HTTP 请求标识
     */
    private void execute(TurnKey key, TurnSession session, SseEmitter stream, String requestId) {
        if (requestId != null) MDC.put(RequestLoggingFilter.REQUEST_ID, requestId);
        StreamEvent terminal;
        try {
            NarrationHttpRequest request = session.request();
            List<ConversationMessage> history = request.history().stream()
                    .map(message -> new ConversationMessage(message.role(), message.text())).toList();
            Consumer<AttemptFailure> onFailure = failure -> send(stream,
                    new StreamEvent("attempt_failed", failure.attempt(), failure.message(), null, null));
            Consumer<NarratedTurn> onCommit = receipt -> send(stream, result("committed", receipt));
            if (request.choiceId() != null) {
                terminal = result("complete", narratives.retryChoice(key.gameId(), request.expectedRevision(), key.turnId(),
                        request.choiceId(), request.locale(), history, onFailure, onCommit));
            } else {
                terminal = narratives.retryNarration(key.gameId(), request.expectedRevision(), key.turnId(),
                                request.playerInput(), request.locale(), history, onFailure, onCommit)
                        .map(turn -> result("complete", turn))
                        .orElseGet(() -> failed("ATTEMPTS_EXHAUSTED", "%d 次尝试均未成功，原文已恢复。"
                                .formatted(NarrativeApplicationService.MAX_MODEL_ATTEMPTS)));
            }
        } catch (NarrativeProviderException failure) {
            terminal = failed("MODEL_UNAVAILABLE", "GM 暂时无法回复，原文已恢复。");
        } catch (GameRequestException failure) {
            terminal = failed(failure.code().name(), switch (failure.code()) {
                case REVISION_MISMATCH -> "游戏状态已更新，请根据当前状态重新发送。";
                case MODEL_CONFIGURATION_REQUIRED -> "请在设置中启用并配置叙事模型。";
                case SAVE_NOT_FOUND -> "这份存档已不存在。";
                default -> session.request().choiceId() == null ? "这条消息暂时无法处理，请检查输入后重试。"
                        : "此行动暂时无法执行，请选择当前可用的行动。";
            });
        } catch (RuntimeException failure) {
            DiagnosticLog.requestFailed(ROUTE, 500, "NARRATION_STREAM_FAILED", null, null, null, failure);
            terminal = failed("RESULT_UNCONFIRMED", "暂时无法确认回合结果，请查询后再继续。");
        } finally {
            MDC.remove(RequestLoggingFilter.REQUEST_ID);
        }
        if ("RESULT_UNCONFIRMED".equals(terminal.code())) {
            synchronized (sessions) { sessions.remove(key, session); }
        }
        session.result().complete(terminal);
        finish(stream, terminal);
    }

    /** 发送终止事件并关闭当前订阅。
     * @param stream 当前流连接
     * @param event 已完成或失败的回合事件
     */
    private static void finish(SseEmitter stream, StreamEvent event) {
        send(stream, event);
        stream.complete();
    }

    /** 发送一个事件，客户端掉线时由回合本身继续处理。
     * @param stream 当前流连接
     * @param event 序列化后发给前端的事件
     */
    private static void send(SseEmitter stream, StreamEvent event) {
        try {
            stream.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException disconnected) {
            // 断线后仍保留回合结果，前端以原标识恢复。
        }
    }

    /** 将权威结果转换为流事件。
     * @param type 已提交或已完成的事件类型
     * @param turn 权威回合数据
     */
    private static StreamEvent result(String type, NarratedTurn turn) {
        return new StreamEvent(type, null, null, null, NarrationHttpResponse.from(turn));
    }

    /** 创建前端可恢复的失败结果。
     * @param code 稳定失败码
     * @param message 简短用户提示
     */
    private static StreamEvent failed(String code, String message) {
        return new StreamEvent("failed", null, message, code, null);
    }

    /** 标识一个游戏内的独立叙事回合。
     * @param gameId 游戏标识
     * @param turnId 回合标识
     */
    private record TurnKey(String gameId, UUID turnId) {}

    /** 短期保留的原请求及最终结果。
     * @param request 客户端初次提交的回合请求
     * @param result 等待或复用该回合结果的句柄
     */
    private record TurnSession(NarrationHttpRequest request, CompletableFuture<StreamEvent> result) {}

    /** 游玩端消费的流式状态。
     * @param type 开始、单次失败、结算、完成或终止失败
     * @param attempt 单次失败的尝试序号，其他事件为空
     * @param message 进度或终止提示，可为空
     * @param code 终止失败的稳定码，可为空
     * @param result 已确认回合，仅在结算或完成时提供
     */
    public record StreamEvent(String type, Integer attempt, String message, String code, NarrationHttpResponse result) {}
}
