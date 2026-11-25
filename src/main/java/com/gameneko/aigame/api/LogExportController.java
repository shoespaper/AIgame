package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.logging.LogTailReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 为本机开发者提供当前日志末尾的附件下载。 */
@RestController
public final class LogExportController {
    private static final DateTimeFormatter EXPORT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final MediaType UTF8_TEXT = MediaType.parseMediaType("text/plain; charset=UTF-8");
    private final Path logFile;
    private final boolean enabled;

    /** 绑定日志输出文件和本机导出开关。
     * @param logFileName 与 Logback 共用的当前日志路径
     * @param enabled 是否启用本机日志导出
     */
    public LogExportController(@Value("${logging.file.name}") String logFileName,
                               @Value("${aigame.logging.export-enabled:true}") boolean enabled) {
        this.logFile = Path.of(logFileName).toAbsolutePath().normalize();
        this.enabled = enabled;
    }

    /** 识别 Servlet 容器返回的本机 IPv4 或 IPv6 地址。
     * @param remoteAddress 直接连接的对端地址
     */
    private static boolean isLocal(String remoteAddress) {
        return "127.0.0.1".equals(remoteAddress) || "::1".equals(remoteAddress)
                || "0:0:0:0:0:0:0:1".equals(remoteAddress) || "::ffff:127.0.0.1".equals(remoteAddress);
    }

    /** 下载当前日志中最近的完整记录，并将空日志及读取失败转换成 API 错误。
     * @param request 当前 Servlet 请求，用于确认连接来自本机
     */
    @GetMapping("/api/settings/logs/export")
    public ResponseEntity<byte[]> export(HttpServletRequest request) {
        if (!enabled || !isLocal(request.getRemoteAddr())) {
            throw new GameRequestException(GameRequestException.Code.LOG_EXPORT_FORBIDDEN, "当前连接无法导出日志。");
        }

        byte[] content;
        try {
            content = LogTailReader.read(logFile);
        } catch (IOException exception) {
            throw new GameRequestException(GameRequestException.Code.LOG_EXPORT_FAILED, "读取日志失败，请稍后重试。", exception);
        }
        if (content.length == 0) {
            throw new GameRequestException(GameRequestException.Code.LOGS_NOT_AVAILABLE, "暂无可导出的日志。");
        }
        String fileName = "aigame-logs-" + EXPORT_TIMESTAMP.format(LocalDateTime.now()) + ".log";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(UTF8_TEXT)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .body(content);
    }
}
