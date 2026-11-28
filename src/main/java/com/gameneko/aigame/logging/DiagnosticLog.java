package com.gameneko.aigame.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.regex.Pattern;

/** 按固定事件结构记录诊断信息，集中限制可写入日志的字段。 */
public final class DiagnosticLog {
    private static final Logger HTTP = LoggerFactory.getLogger("com.gameneko.aigame.api.diagnostics");
    private static final Logger MODEL = LoggerFactory.getLogger("com.gameneko.aigame.narrative.diagnostics");
    private static final Logger STORAGE = LoggerFactory.getLogger("com.gameneko.aigame.persistence.diagnostics");
    private static final String MODEL_CALL_INDEX = DiagnosticLog.class.getName() + ".modelCallIndex";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");

    /** 工具类无需实例。 */
    private DiagnosticLog() {
    }

    /** 记录一次 HTTP 请求的最终状态与耗时。
     * @param method Servlet 请求方法
     * @param route MVC 路由模板
     * @param gameId 可空的路由游戏标识
     * @param status HTTP 响应状态码
     * @param durationMs 服务端耗时，单位毫秒
     * @param healthProbe 是否为成功的健康检查
     */
    public static void requestCompleted(String method, String route, String gameId, int status, long durationMs, boolean healthProbe) {
        LoggingEventBuilder event = healthProbe ? HTTP.atDebug() : HTTP.atInfo();
        event.addKeyValue("method", identifier(method)).addKeyValue("route", route)
                .addKeyValue("gameId", identifier(gameId)).addKeyValue("status", status)
                .addKeyValue("durationMs", durationMs).log("http.request.completed");
    }

    /** 记录统一错误出口的响应与可用诊断信息。
     * @param route MVC 路由模板
     * @param status HTTP 响应状态码
     * @param code 稳定错误码
     * @param action 可空的动作上下文
     * @param storage 可空的持久化上下文
     * @param model 可空的上游失败原因
     * @param unexpected 可空的意外异常，仅提取安全堆栈
     */
    public static void requestFailed(String route, int status, String code, Action action, Storage storage,
                                     ModelFailure model, Throwable unexpected) {
        LoggingEventBuilder event = status >= 500 ? HTTP.atError() : HTTP.atInfo();
        event.addKeyValue("route", route).addKeyValue("status", status).addKeyValue("code", identifier(code));
        if (action != null) {
            event.addKeyValue("gameId", identifier(action.gameId())).addKeyValue("engineId", identifier(action.engineId()))
                    .addKeyValue("actionType", identifier(action.actionType()))
                    .addKeyValue("expectedRevision", action.expectedRevision()).addKeyValue("actualRevision", action.actualRevision());
        }
        if (storage != null) {
            event.addKeyValue("resourceType", identifier(storage.resourceType())).addKeyValue("resourceId", identifier(storage.resourceId()))
                    .addKeyValue("operation", identifier(storage.operation())).addKeyValue("stage", identifier(storage.stage()))
                    .addKeyValue("exceptionType", storage.cause().getClass().getName()).addKeyValue("stack", safeStack(storage.cause()));
        }
        if (model != null) {
            event.addKeyValue("reason", identifier(model.reason())).addKeyValue("upstreamStatus", model.status());
        }
        if (unexpected != null) {
            event.addKeyValue("exceptionType", unexpected.getClass().getName()).addKeyValue("stack", safeStack(unexpected));
        }
        event.log(storage != null ? "persistence.operation.failed" : status >= 500 ? "http.request.failed" : "http.request.rejected");
    }

    /** 记录存档实际写入成功后的标识与版本。
     * @param gameId 游戏标识
     * @param worldId 世界书标识
     * @param engineId 可空的引擎标识
     * @param revision 已写入的修订号
     */
    public static void saveCommitted(String gameId, String worldId, String engineId, long revision) {
        STORAGE.atInfo().addKeyValue("gameId", identifier(gameId)).addKeyValue("worldId", identifier(worldId))
                .addKeyValue("engineId", identifier(engineId)).addKeyValue("revision", revision).log("game.save.completed");
    }

    /** 记录游戏动作接口成功持久化后的结果。
     * @param action 动作类型、游戏标识和已写入版本
     */
    public static void actionCommitted(Action action) {
        HTTP.atInfo().addKeyValue("gameId", identifier(action.gameId())).addKeyValue("engineId", identifier(action.engineId()))
                .addKeyValue("actionType", identifier(action.actionType())).addKeyValue("expectedRevision", action.expectedRevision())
                .addKeyValue("actualRevision", action.actualRevision()).log("game.action.committed");
    }

    /** 记录一次模型对话、结果和可用的重试计划。
     * @param attempt 单次模型尝试的消息内容和指标
     * @param outcome 固定的结果标识
     * @param reason 可空的失败原因码
     * @param retryDelayMs 下一次尝试前的等待毫秒数，未重试时为空
     */
    public static void modelAttempt(ModelAttempt attempt, String outcome, String reason, Long retryDelayMs) {
        LoggingEventBuilder event = switch (outcome) {
            case "failed" -> MODEL.atError();
            case "retry" -> MODEL.atWarn();
            default -> MODEL.atInfo();
        };
        modelCallFields(event, attempt.call());
        if (attempt.upstreamError() != null) {
            UpstreamModelError error = attempt.upstreamError();
            event.addKeyValue("upstreamDiagnosis", identifier(error.diagnosis()))
                    .addKeyValue("diagnosisSource", identifier(error.source()))
                    .addKeyValue("upstreamErrorCode", identifier(error.code()))
                    .addKeyValue("upstreamErrorParam", identifier(error.parameter()))
                    .addKeyValue("upstreamRequestId", identifier(error.requestId()));
        }
        if (attempt.outputError() != null) {
            ModelOutputError error = attempt.outputError();
            event.addKeyValue("field", error.field()).addKeyValue("expectedType", identifier(error.expectedType()))
                    .addKeyValue("line", error.line()).addKeyValue("column", error.column());
        }
        event.addKeyValue("model", identifier(attempt.model())).addKeyValue("attempt", attempt.number())
                .addKeyValue("upstreamStatus", attempt.status()).addKeyValue("maxTokens", attempt.maxTokens())
                .addKeyValue("durationMs", attempt.durationMs()).addKeyValue("promptTokens", attempt.promptTokens())
                .addKeyValue("completionTokens", attempt.completionTokens()).addKeyValue("proposedActionType", identifier(attempt.proposedActionType()))
                .addKeyValue("systemMessage", attempt.systemMessage()).addKeyValue("userMessage", attempt.userMessage())
                .addKeyValue("modelResponse", attempt.modelResponse()).addKeyValue("modelRefusal", attempt.modelRefusal())
                .addKeyValue("outcome", identifier(outcome)).addKeyValue("reason", identifier(reason))
                .addKeyValue("delayMs", retryDelayMs).log(retryDelayMs == null ? "narrative.completed" : "narrative.retry");
    }

    /** 分配当前 HTTP 请求内的逻辑模型调用序号。
     * @param operation 固定的业务阶段标识
     * @param format 实际请求的响应格式
     */
    public static ModelCall modelCall(String operation, String format) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Integer index = currentModelCallIndex();
        if (request != null) {
            index = index == null ? 1 : index + 1;
            request.setAttribute(MODEL_CALL_INDEX, index, RequestAttributes.SCOPE_REQUEST);
        } else if (index != null) {
            index++;
            ASYNC_MODEL_CALL_INDEX.set(index);
        }
        return new ModelCall(operation, index, format);
    }

    /** 获取当前请求内最近的逻辑模型调用序号，后台调用时为空。 */
    private static Integer currentModelCallIndex() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return request == null ? ASYNC_MODEL_CALL_INDEX.get()
                : (Integer) request.getAttribute(MODEL_CALL_INDEX, RequestAttributes.SCOPE_REQUEST);
    }

    private static final ThreadLocal<Integer> ASYNC_MODEL_CALL_INDEX = new ThreadLocal<>();

    /** 为不持有 Servlet 请求的流式工作线程建立调用序号作用域。
     * @param work 执行一次逻辑回合的任务
     */
    public static void withAsyncModelCalls(Runnable work) {
        ASYNC_MODEL_CALL_INDEX.set(0);
        try {
            work.run();
        } finally {
            ASYNC_MODEL_CALL_INDEX.remove();
        }
    }

    /** 写入可关联逻辑调用的通用字段。
     * @param event 待写入的模型日志事件
     * @param call 逻辑调用元数据
     */
    private static void modelCallFields(LoggingEventBuilder event, ModelCall call) {
        event.addKeyValue("operation", identifier(call.operation())).addKeyValue("callIndex", call.index())
                .addKeyValue("responseFormat", identifier(call.format()));
    }

    /** 记录上游调用阶段的调试耗时。
     * @param call 本次逻辑模型调用
     * @param attempt 从 1 开始的网络尝试次数
     * @param status 可用的上游 HTTP 状态码
     * @param durationMs 上游阶段耗时，单位毫秒
     */
    public static void modelUpstream(ModelCall call, int attempt, Integer status, long durationMs) {
        LoggingEventBuilder event = MODEL.atDebug();
        modelCallFields(event, call);
        event.addKeyValue("attempt", attempt).addKeyValue("upstreamStatus", status)
                .addKeyValue("durationMs", durationMs).log("narrative.upstream.completed");
    }

    /** 记录上游响应解析成功后的业务验收结果。
     * @param operation 固定的业务阶段标识
     * @param outcome 通过、退回或无效输出
     * @param reason 可空的固定原因码
     * @param field 可空的固定协议字段名
     * @param actual 可空的实际值，例如正文字符数或 Judge 问题数
     * @param limit 可空的字段上限
     */
    public static void modelValidation(String operation, String outcome, String reason, String field,
                                       Integer actual, Integer limit) {
        LoggingEventBuilder event = "invalid".equals(outcome) ? MODEL.atWarn() : MODEL.atInfo();
        event.addKeyValue("operation", identifier(operation)).addKeyValue("callIndex", currentModelCallIndex())
                .addKeyValue("outcome", identifier(outcome)).addKeyValue("reason", identifier(reason))
                .addKeyValue("field", identifier(field)).addKeyValue("actual", actual).addKeyValue("limit", limit)
                .log("narrative.validation.completed");
    }

    /** 记录模型输出截断后的预算变化。
     * @param call 本次逻辑模型调用
     * @param attempt 发生截断的网络尝试次数，从 1 开始
     * @param current 本次输出 token 上限
     * @param next 下次输出 token 上限
     */
    public static void modelBudgetChanged(ModelCall call, int attempt, int current, int next) {
        LoggingEventBuilder event = MODEL.atDebug();
        modelCallFields(event, call);
        event.addKeyValue("attempt", attempt).addKeyValue("maxTokens", current)
                .addKeyValue("nextMaxTokens", next).log("narrative.budget.changed");
    }

    /** 记录每次模板提案的失败阶段和校验代码位置。
     * @param templateId 当前模板标识
     * @param attempt 从 1 开始的已失败尝试次数
     * @param stage 提案解析或内容校验阶段的固定标识
     * @param failure 通过调用栈定位校验检查的异常
     */
    public static void templateProposalRejected(String templateId, int attempt, String stage, IllegalArgumentException failure) {
        MODEL.atWarn().addKeyValue("templateId", identifier(templateId)).addKeyValue("attempt", attempt)
                .addKeyValue("reason", "invalid_template_proposal").addKeyValue("stage", identifier(stage))
                .addKeyValue("stack", safeStack(failure)).log("template.proposal.rejected");
    }

    /** 检查标识格式，避免自由文本作为日志字段内容。
     * @param value 可空的标识字符串
     */
    private static String identifier(String value) {
        if (value == null) return null;
        return IDENTIFIER.matcher(value).matches() ? value : "invalid_identifier";
    }

    /** 提取有限的代码位置和异常类型，省略异常消息。
     * @param failure 原始异常
     */
    private static String safeStack(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            if (depth > 0) result.append("\nCaused by: ");
            result.append(cause.getClass().getName());
            for (Throwable suppressed : cause.getSuppressed()) {
                result.append("\nSuppressed: ").append(suppressed.getClass().getName());
            }
            StackTraceElement[] frames = cause.getStackTrace();
            for (int index = 0; index < Math.min(16, frames.length); index++) {
                result.append("\n  at ").append(frames[index]);
            }
        }
        return result.toString();
    }

    /** 动作日志字段。
     * @param gameId 游戏标识
     * @param engineId 可空的引擎标识
     * @param actionType 可空的动作类型
     * @param expectedRevision 客户端提交的修订号
     * @param actualRevision 权威修订号
     */
    public record Action(String gameId, String engineId, String actionType, long expectedRevision, long actualRevision) {}

    /** 持久化失败日志字段。
     * @param resourceType 资源类别
     * @param resourceId 可空的资源标识
     * @param operation 存储操作标识
     * @param stage 失败阶段标识
     * @param cause 用于提取异常类型的底层原因
     */
    public record Storage(String resourceType, String resourceId, String operation, String stage, Throwable cause) {}

    /** 模型失败日志字段。
     * @param reason 稳定原因码
     * @param status 可空的上游 HTTP 状态码
     */
    public record ModelFailure(String reason, Integer status) {}

    /** 单次模型尝试的对话内容和指标。
     * @param model 模型标识
     * @param number 从 1 开始的尝试次数
     * @param status 可空的上游 HTTP 状态码
     * @param maxTokens 输出 token 上限
     * @param durationMs 本次尝试耗时，单位毫秒
     * @param promptTokens 可用的输入 token 数
     * @param completionTokens 可用的输出 token 数
     * @param proposedActionType 可空的提议动作类型
     * @param systemMessage 发往模型的系统消息，未构造时为空
     * @param userMessage 发往模型的用户消息，未编码时为空
     * @param modelResponse 模型在解析前的回复正文，上游未返回正文时为空
     * @param modelRefusal 模型返回的拒绝说明，未返回时为空
     * @param call 逻辑调用与响应格式
     * @param upstreamError 可空的安全上游错误诊断
     * @param outputError 可空的结构化回复解析详情
     */
    public record ModelAttempt(String model, int number, Integer status, int maxTokens, long durationMs,
                               Integer promptTokens, Integer completionTokens, String proposedActionType,
                               String systemMessage, String userMessage, String modelResponse, String modelRefusal,
                               ModelCall call, UpstreamModelError upstreamError, ModelOutputError outputError) {}

    /** 同一逻辑模型调用共用的字段。
     * @param operation 固定业务阶段标识
     * @param index 当前 HTTP 请求内的调用序号，后台调用时为空
     * @param format 实际请求的响应格式
     */
    public record ModelCall(String operation, Integer index, String format) {}

    /** 经白名单或规则映射得到的上游错误信息。
     * @param diagnosis 本地稳定诊断码
     * @param source 诊断依据，取 upstream_error、request_inspection、http_status 或 unknown
     * @param code 可空的白名单上游错误码
     * @param parameter 可空的白名单上游参数名
     * @param requestId 可空的安全上游请求 ID
     */
    public record UpstreamModelError(String diagnosis, String source, String code, String parameter, String requestId) {}

    /** 安全的模型输出解析位置。
     * @param field 可空的协议字段路径，动态字段名用星号表示
     * @param expectedType 可空的目标 Java 类型
     * @param line 可空的行号
     * @param column 可空的列号
     */
    public record ModelOutputError(String field, String expectedType, Integer line, Integer column) {}
}
