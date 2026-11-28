package com.gameneko.aigame.api;

import com.gameneko.aigame.logging.DiagnosticLog;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 为 API 请求关联请求 ID，并在请求结束时记录状态与耗时。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
final class RequestLoggingFilter extends OncePerRequestFilter {
    static final String REQUEST_ID = "requestId";
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** 跳过游戏 API 和 Actuator 健康检查以外的请求。
     * @param request 当前 Servlet 请求
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api") && !path.startsWith("/api/")
                && !path.equals("/actuator/health") && !path.startsWith("/actuator/health/");
    }

    /** 建立请求日志上下文，完成后恢复线程原有的 MDC 值。
     * @param request 当前 Servlet 请求
     * @param response 当前 HTTP 响应
     * @param chain 后续过滤器与 MVC 的调用链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get(REQUEST_ID);
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        MDC.put(REQUEST_ID, requestId);
        boolean completed = false;
        try {
            response.setHeader(REQUEST_ID_HEADER, requestId);
            chain.doFilter(request, response);
            completed = true;
        } finally {
            try {
                String route = routeTemplate(request);
                String method = request.getMethod();
                String gameId = gameId(request);
                if (!request.isAsyncStarted() || !logAfterAsync(request, response, requestId, method, route, gameId, started)) {
                    int status = completed || response.isCommitted() ? response.getStatus() : 500;
                    logCompletion(method, route, gameId, status, started);
                }
            } finally {
                if (previous == null) MDC.remove(REQUEST_ID);
                else MDC.put(REQUEST_ID, previous);
            }
        }
    }

    /** 在流式响应真正完成时记录请求摘要。
     * @param request 当前 Servlet 请求
     * @param response 当前 Servlet 响应
     * @param requestId 请求关联标识
     * @param method HTTP 方法
     * @param route 已匹配的路由模板
     * @param gameId 可空的游戏存档标识
     * @param started 请求开始时的单调时钟纳秒值
     */
    private static boolean logAfterAsync(HttpServletRequest request, HttpServletResponse response, String requestId,
                                         String method, String route, String gameId, long started) {
        try {
            request.getAsyncContext().addListener(new AsyncListener() {
                /** 使用原请求上下文记录异步响应的最终状态。@param event Servlet 完成事件 */
                @Override public void onComplete(AsyncEvent event) {
                    String previous = MDC.get(REQUEST_ID);
                    MDC.put(REQUEST_ID, requestId);
                    try { logCompletion(method, route, gameId, response.getStatus(), started); }
                    finally {
                        if (previous == null) MDC.remove(REQUEST_ID);
                        else MDC.put(REQUEST_ID, previous);
                    }
                }

                /** 等待最终完成事件记录摘要。@param event Servlet 超时事件 */
                @Override public void onTimeout(AsyncEvent event) {}

                /** 等待最终完成事件记录摘要。@param event Servlet 错误事件 */
                @Override public void onError(AsyncEvent event) {}

                /** 保留后续异步调度的完成监听。@param event Servlet 重新开始异步的事件 */
                @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
            });
            return true;
        } catch (IllegalStateException completed) {
            return false;
        }
    }

    /** 记录统一格式的请求完成日志。
     * @param method HTTP 方法
     * @param route 匹配的路由模板
     * @param gameId 可空的游戏标识
     * @param status 最终 HTTP 状态
     * @param started 请求开始时的单调时钟纳秒值
     */
    private static void logCompletion(String method, String route, String gameId, int status, long started) {
        boolean healthy = status < 400 && (route.equals("/api/health") || route.equals("/actuator/health")
                || route.startsWith("/actuator/health/"));
        DiagnosticLog.requestCompleted(method, route, gameId, status,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), healthy);
    }

    /** 从匹配的路由变量取得游戏 ID，不读取请求体。
     * @param request 当前 Servlet 请求
     */
    private static String gameId(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> values && values.get("gameId") instanceof String id ? id : null;
    }

    /** 取得框架匹配的路由模板，未知路由使用固定占位符。
     * @param request 当前 Servlet 请求
     */
    static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern == null ? "unmatched" : pattern.toString();
    }
}
