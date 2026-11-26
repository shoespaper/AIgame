package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.content.WorldContentException;
import com.gameneko.aigame.engine.WorldState.WorldActionRejected;
import com.gameneko.aigame.logging.DiagnosticLog;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.application.port.GamePersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 在 HTTP 边界统一映射已知错误并记录请求最终失败。 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    /** 映射明确的请求错误码，附加可用的动作版本上下文。
     * @param exception 已分类的请求错误
     * @param request 当前 Servlet 请求
     */
    @ExceptionHandler(GameRequestException.class)
    public ResponseEntity<ErrorResponse> handleGameRequest(GameRequestException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.code()) {
            case INVALID_REQUEST, INVALID_INITIALIZATION_ANSWERS, UNSUPPORTED_ENGINE -> HttpStatus.BAD_REQUEST;
            case SAVE_NOT_FOUND, SESSION_NOT_FOUND, CONTENT_NOT_FOUND, LOGS_NOT_AVAILABLE -> HttpStatus.NOT_FOUND;
            case REVISION_MISMATCH, CONTENT_ALREADY_EXISTS, MODEL_CONFIGURATION_REQUIRED -> HttpStatus.CONFLICT;
            case CONTENT_READ_ONLY, LOG_EXPORT_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ENGINE_REQUIRED, ENGINE_ACTION_REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case LOG_EXPORT_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        GameRequestException.ActionContext context = exception.actionContext();
        DiagnosticLog.Action action = context == null ? null : new DiagnosticLog.Action(context.gameId(), context.engineId(),
                context.actionType(), context.expectedRevision(), context.actualRevision());
        String code = exception.code().name();
        DiagnosticLog.requestFailed(RequestLoggingFilter.routeTemplate(request), status.value(), code, action, null, null,
                status.is5xxServerError() ? exception : null);
        return error(status, code, exception.getMessage());
    }

    /** 处理内容选择错误，并把服务器内容读取失败记录为内部故障。
     * @param exception 世界书或模板目录抛出的错误
     * @param request 当前 Servlet 请求
     */
    @ExceptionHandler(WorldContentException.class)
    public ResponseEntity<ErrorResponse> handleWorldContent(WorldContentException exception, HttpServletRequest request) {
        if (exception.unavailable()) {
            return failed(request, HttpStatus.INTERNAL_SERVER_ERROR, "WORLD_CONTENT_UNAVAILABLE", "Unable to load world content", exception);
        }
        return failed(request, HttpStatus.BAD_REQUEST, "INVALID_WORLD_CONTENT", exception.getMessage(), null);
    }

    /** 区分 JSON 解码问题与在请求对象中确认的业务校验失败。
     * @param exception MVC 抛出的请求体解码错误
     * @param request 当前 Servlet 请求
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException exception,
                                                                 HttpServletRequest request) {
        if (exception.getMostSpecificCause() instanceof GameRequestException failure
                && failure.code() == GameRequestException.Code.INVALID_REQUEST) {
            return handleGameRequest(failure, request);
        }
        return failed(request, HttpStatus.BAD_REQUEST, "INVALID_JSON", "Request body must be valid JSON", null);
    }

    /** 记录持久化失败的资源和阶段，保留底层原因用于诊断。
     * @param exception 持久化边界抛出的存储错误
     * @param request 当前 Servlet 请求
     */
    @ExceptionHandler(GamePersistenceException.class)
    public ResponseEntity<ErrorResponse> handlePersistenceError(GamePersistenceException exception, HttpServletRequest request) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        DiagnosticLog.Storage storage = new DiagnosticLog.Storage(exception.resourceType(), exception.resourceId(),
                exception.operation(), exception.stage(), cause);
        DiagnosticLog.requestFailed(RequestLoggingFilter.routeTemplate(request), 500, "PERSISTENCE_FAILED", null, storage, null, null);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "PERSISTENCE_FAILED", "Unable to read or write game data");
    }

    /** 处理世界状态明确拒绝的动作。
     * @param exception 世界动作拒绝
     * @param request 当前 Servlet 请求
     */
    @ExceptionHandler(WorldActionRejected.class)
    public ResponseEntity<ErrorResponse> handleWorldAction(WorldActionRejected exception, HttpServletRequest request) {
        return failed(request, HttpStatus.UNPROCESSABLE_ENTITY, "WORLD_ACTION_REJECTED", exception.getMessage(), null);
    }

    /** 处理上游模型请求或输出失败。
     * @param exception 模型边界报告的失败
     * @param request 当前 Servlet 请求
     */
    @ExceptionHandler(NarrativeProviderException.class)
    public ResponseEntity<ErrorResponse> handleNarrativeProvider(NarrativeProviderException exception, HttpServletRequest request) {
        DiagnosticLog.ModelFailure model = new DiagnosticLog.ModelFailure(exception.reasonCode(), exception.upstreamStatus());
        DiagnosticLog.requestFailed(RequestLoggingFilter.routeTemplate(request), 502, "NARRATIVE_PROVIDER_FAILED", null, null, model, null);
        String message = exception.getMessage() == null ? "叙事模型调用失败" : exception.getMessage();
        return error(HttpStatus.BAD_GATEWAY, "NARRATIVE_PROVIDER_FAILED", message);
    }

    /** 保留 MVC 自带的状态，其他异常统一返回内部错误。
     * @param exception 未由其他处理器接收的异常
     * @param request 当前 Servlet 请求
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof org.springframework.web.ErrorResponse response) {
            HttpStatusCode status = response.getStatusCode();
            String code = "HTTP_" + status.value();
            DiagnosticLog.requestFailed(RequestLoggingFilter.routeTemplate(request), status.value(), code, null, null, null,
                    status.is5xxServerError() ? exception : null);
            HttpStatus standard = HttpStatus.resolve(status.value());
            String message = standard == null ? "Request failed" : standard.getReasonPhrase();
            return ResponseEntity.status(status).headers(response.getHeaders()).cacheControl(CacheControl.noStore())
                    .body(ErrorResponse.current(message, code));
        }
        return failed(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error", exception);
    }

    /** 记录不需要附加业务字段的失败并生成响应。
     * @param request 当前 Servlet 请求
     * @param status 响应状态
     * @param code 稳定错误码
     * @param message 用户可读的提示
     * @param cause 可空的意外异常
     */
    private static ResponseEntity<ErrorResponse> failed(HttpServletRequest request, HttpStatus status, String code,
                                                        String message, Throwable cause) {
        DiagnosticLog.requestFailed(RequestLoggingFilter.routeTemplate(request), status.value(), code, null, null, null, cause);
        return error(status, code, message);
    }

    /** 返回禁止缓存的错误响应。
     * @param status 响应状态
     * @param code 稳定错误码
     * @param message 用户可读的提示
     */
    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ErrorResponse.current(message, code));
    }

    /** API 错误体，保留客户端使用的错误提示并附上定位字段。
     * @param error 用户可读的提示
     * @param code 稳定错误码
     * @param requestId 当前请求 ID，非 HTTP 调用时可为空
     */
    public record ErrorResponse(String error, String code, String requestId) {
        /** 从当前线程的请求上下文生成错误体。
         * @param error 用户可读的提示
         * @param code 稳定错误码
         */
        static ErrorResponse current(String error, String code) {
            return new ErrorResponse(error, code, MDC.get(RequestLoggingFilter.REQUEST_ID));
        }
    }
}
