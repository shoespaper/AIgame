package com.gameneko.aigame.api;

import com.gameneko.aigame.logging.DiagnosticLog;
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
                int status = completed || response.isCommitted() ? response.getStatus() : 500;
                String route = routeTemplate(request);
                boolean healthy = status < 400 && (route.equals("/api/health") || route.equals("/actuator/health")
                        || route.startsWith("/actuator/health/"));
                DiagnosticLog.requestCompleted(request.getMethod(), route, gameId(request), status,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), healthy);
            } finally {
                if (previous == null) MDC.remove(REQUEST_ID);
                else MDC.put(REQUEST_ID, previous);
            }
        }
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
