package com.gameneko.aigame.application;

import com.gameneko.aigame.engine.GameInstance;

/** 表示请求处置中的已知失败，携带稳定错误码与可选的动作上下文。 */
public final class GameRequestException extends IllegalArgumentException {
    /** 供 HTTP 层和前端识别的稳定错误码。 */
    public enum Code {
        INVALID_REQUEST, INVALID_INITIALIZATION_ANSWERS, ENGINE_REQUIRED, ENGINE_ACTION_REJECTED,
        UNSUPPORTED_ENGINE, REVISION_MISMATCH, SAVE_NOT_FOUND, SESSION_NOT_FOUND,
        CONTENT_NOT_FOUND, CONTENT_ALREADY_EXISTS, CONTENT_READ_ONLY, MODEL_CONFIGURATION_REQUIRED,
        LOGS_NOT_AVAILABLE, LOG_EXPORT_FORBIDDEN, LOG_EXPORT_FAILED,
        WORLD_DRAFT_ATTEMPTS_EXHAUSTED, TEMPLATE_ATTEMPTS_EXHAUSTED
    }

    /** 引擎中立的动作和存档版本上下文。
     * @param gameId 游戏标识
     * @param engineId 可空的引擎标识
     * @param actionType 可空的动作类型
     * @param expectedRevision 客户端提交的修订号
     * @param actualRevision 当前权威修订号
     */
    public record ActionContext(String gameId, String engineId, String actionType, long expectedRevision, long actualRevision) {}

    private final Code code;
    private final ActionContext actionContext;

    /** 创建已知的请求错误。
     * @param code 稳定错误码
     * @param message 用户可读的提示
     */
    public GameRequestException(Code code, String message) {
        this(code, message, null, null);
    }

    /** 创建保留底层原因的已知请求错误。
     * @param code 稳定错误码
     * @param message 用户可读的提示
     * @param cause 底层原因
     */
    public GameRequestException(Code code, String message, Throwable cause) {
        this(code, message, cause, null);
    }

    /** 创建带可选原因和动作上下文的错误。
     * @param code 稳定错误码
     * @param message 用户可读的提示
     * @param cause 可空的底层原因
     * @param actionContext 可空的动作上下文
     */
    private GameRequestException(Code code, String message, Throwable cause, ActionContext actionContext) {
        super(message, cause);
        this.code = code;
        this.actionContext = actionContext;
    }

    /** 创建参数校验失败。
     * @param message 用户可读的校验提示
     */
    public static GameRequestException invalid(String message) {
        return new GameRequestException(Code.INVALID_REQUEST, message);
    }

    /** 返回稳定错误码。 */
    public Code code() {
        return code;
    }

    /** 返回可空的动作上下文。 */
    public ActionContext actionContext() {
        return actionContext;
    }

    /** 校验客户端修订号，冲突时附带当前和预期版本。
     * @param instance 当前权威游戏实例
     * @param expectedRevision 客户端提交的修订号
     * @param actionType 可空的动作类型
     */
    static void checkRevision(GameInstance<?> instance, long expectedRevision, String actionType) {
        if (expectedRevision == instance.revision()) return;
        throw action(Code.REVISION_MISMATCH,
                "Revision mismatch: expected " + expectedRevision + ", current " + instance.revision(),
                instance, actionType, expectedRevision);
    }

    /** 创建带动作上下文的预期拒绝。
     * @param code 稳定的动作错误码
     * @param message 用户可读的拒绝原因
     * @param instance 当前权威游戏实例
     * @param actionType 可空的动作类型
     * @param expectedRevision 客户端提交的修订号
     */
    static GameRequestException action(Code code, String message, GameInstance<?> instance,
                                       String actionType, long expectedRevision) {
        ActionContext context = new ActionContext(instance.gameId(), instance.rulesetId(), actionType, expectedRevision, instance.revision());
        return new GameRequestException(code, message, null, context);
    }
}
