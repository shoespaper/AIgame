package com.gameneko.aigame.api;

import com.gameneko.aigame.application.CreationProgress;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.logging.DiagnosticLog;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;

/** 将世界草案或开局确认的进度和最终结果写入流式 HTTP 响应。 */
final class CreationStream {
    private CreationStream() {}

    /** 在独立工作线程处理生成流程并单独发送阶段、失败和最终结果。
     * @param route 用于诊断的固定 API 路由
     * @param work 接收进度回调并返回最终 DTO 的生成操作
     * @param <T> 本次操作的响应类型
     */
    static <T> SseEmitter start(String route, Function<Consumer<CreationProgress>, T> work) {
        SseEmitter stream = new SseEmitter(1_200_000L);
        stream.onTimeout(stream::complete);
        String requestId = MDC.get(RequestLoggingFilter.REQUEST_ID);
        Thread.startVirtualThread(() -> DiagnosticLog.withAsyncModelCalls(() -> execute(stream, route, work, requestId)));
        return stream;
    }

    /** 执行生成工作并保留请求日志关联。
     * @param stream 当前 SSE 连接
     * @param route 固定 API 路由
     * @param work 负责推进业务并产生结果的操作
     * @param requestId 可空的 HTTP 请求标识
     * @param <T> 本次操作的响应类型
     */
    private static <T> void execute(SseEmitter stream, String route, Function<Consumer<CreationProgress>, T> work,
                                    String requestId) {
        if (requestId != null) MDC.put(RequestLoggingFilter.REQUEST_ID, requestId);
        try {
            T result = work.apply(progress -> send(stream, new Event("progress", progress, null, null, null)));
            send(stream, new Event("complete", null, result, null, null));
        } catch (GameRequestException failure) {
            send(stream, new Event("failed", null, null, failure.code().name(), failure.getMessage()));
        } catch (NarrativeProviderException failure) {
            send(stream, new Event("failed", null, null, "NARRATIVE_PROVIDER_FAILED", "模型暂时无法完成生成，请稍后重试。"));
        } catch (RuntimeException failure) {
            DiagnosticLog.requestFailed(route, 500, "CREATION_STREAM_FAILED", null, null, null, failure);
            send(stream, new Event("failed", null, null, "INTERNAL_ERROR", "暂时无法完成操作，请重试。"));
        } finally {
            MDC.remove(RequestLoggingFilter.REQUEST_ID);
            stream.complete();
        }
    }

    /** 发送单条事件，连接断开时保留业务层已经完成的写入。
     * @param stream 当前流连接
     * @param event 面向前端的进度或结果
     */
    private static void send(SseEmitter stream, Event event) {
        try {
            stream.send(SseEmitter.event().data(event, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException disconnected) {
            // 刷新或关闭页面时，服务端仍按会话规则完成当前操作。
        }
    }

    /** 创建流程的流式事件。
     * @param type 进度、完成或失败
     * @param progress 当前阶段及次数，仅进度事件提供
     * @param result 正式响应，仅完成事件提供
     * @param code 稳定错误码，仅失败事件提供
     * @param message 用户可读错误，仅失败事件提供
     */
    private record Event(String type, CreationProgress progress, Object result, String code, String message) {}
}
