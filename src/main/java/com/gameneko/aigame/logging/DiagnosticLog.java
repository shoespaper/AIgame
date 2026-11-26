package com.gameneko.aigame.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.regex.Pattern;

/** 按固定事件结构记录诊断信息，集中限制可写入日志的字段。 */
public final class DiagnosticLog {
    private static final Logger HTTP = LoggerFactory.getLogger("com.gameneko.aigame.api.diagnostics");
    private static final Logger MODEL = LoggerFactory.getLogger("com.gameneko.aigame.narrative.diagnostics");
    private static final Logger STORAGE = LoggerFactory.getLogger("com.gameneko.aigame.persistence.diagnostics");
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
        LoggingEventBuilder event = retryDelayMs == null ? MODEL.atInfo() : MODEL.atWarn();
        event.addKeyValue("model", identifier(attempt.model())).addKeyValue("attempt", attempt.number())
                .addKeyValue("upstreamStatus", attempt.status()).addKeyValue("maxTokens", attempt.maxTokens())
                .addKeyValue("durationMs", attempt.durationMs()).addKeyValue("promptTokens", attempt.promptTokens())
                .addKeyValue("completionTokens", attempt.completionTokens()).addKeyValue("proposedActionType", identifier(attempt.proposedActionType()))
                .addKeyValue("systemMessage", attempt.systemMessage()).addKeyValue("userMessage", attempt.userMessage())
                .addKeyValue("modelResponse", attempt.modelResponse()).addKeyValue("modelRefusal", attempt.modelRefusal())
                .addKeyValue("outcome", identifier(outcome)).addKeyValue("reason", identifier(reason))
                .addKeyValue("delayMs", retryDelayMs).log(retryDelayMs == null ? "narrative.completed" : "narrative.retry");
    }

    /** 记录上游调用阶段的调试耗时。
     * @param attempt 从 1 开始的尝试次数
     * @param status 可用的上游 HTTP 状态码
     * @param durationMs 上游阶段耗时，单位毫秒
     */
    public static void modelUpstream(int attempt, Integer status, long durationMs) {
        MODEL.atDebug().addKeyValue("attempt", attempt).addKeyValue("upstreamStatus", status)
                .addKeyValue("durationMs", durationMs).log("narrative.upstream.completed");
    }

    /** 记录模型输出截断后的预算变化。
     * @param current 本次输出 token 上限
     * @param next 下次输出 token 上限
     */
    public static void modelBudgetChanged(int current, int next) {
        MODEL.atDebug().addKeyValue("maxTokens", current).addKeyValue("nextMaxTokens", next).log("narrative.budget.changed");
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

    /** 记录提案重试耗尽后跳过本局模板登记。
     * @param gameId 本局游戏标识
     * @param templateId 未能登记的模板标识
     * @param engineId 所选引擎标识，纯叙事时为空字符串
     */
    public static void templateRegistrationSkipped(String gameId, String templateId, String engineId) {
        MODEL.atWarn().addKeyValue("gameId", identifier(gameId)).addKeyValue("templateId", identifier(templateId))
                .addKeyValue("engineId", engineId.isBlank() ? null : identifier(engineId))
                .addKeyValue("reason", "invalid_template_proposal").log("template.registration.skipped");
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
     */
    public record ModelAttempt(String model, int number, Integer status, int maxTokens, long durationMs,
                               Integer promptTokens, Integer completionTokens, String proposedActionType,
                               String systemMessage, String userMessage, String modelResponse, String modelRefusal) {}
}
