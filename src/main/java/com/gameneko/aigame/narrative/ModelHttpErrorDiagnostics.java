package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.logging.DiagnosticLog.UpstreamModelError;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 从上游拒绝中提取可写入日志的固定诊断，错误响应原文只用于内存分类。 */
final class ModelHttpErrorDiagnostics {
    private static final int MAX_ERROR_BODY_BYTES = 16_384;
    private static final List<String> ERROR_CODES = List.of("invalid_request_error", "invalid_parameter",
            "unsupported_value", "model_not_found", "invalid_api_key", "insufficient_quota",
            "context_length_exceeded", "rate_limit_exceeded", "server_error");
    private static final List<String> PARAMETERS = List.of("response_format", "messages", "max_tokens", "model");
    private static final Pattern REQUEST_ID = Pattern.compile(
            "(?:req_[0-9a-fA-F]{16,64}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");

    /** 诊断工具无需实例。 */
    private ModelHttpErrorDiagnostics() {}

    /** 分类 HTTP 拒绝，并区分上游证据与从实际请求得到的推断。
     * @param mapper JSON 错误响应的映射器
     * @param exception 带上游状态、响应头及响应体的异常
     * @param format 实际发送的响应格式
     * @param system 实际发送给模型的系统消息
     * @param user 实际发送给模型的用户消息
     */
    static UpstreamModelError classify(ObjectMapper mapper, RestClientResponseException exception,
                                       String format, String system, String user) {
        JsonNode error = errorObject(mapper, exception.getResponseBodyAsByteArray());
        String code = allowed(error.path("code").textValue(), ERROR_CODES);
        String parameter = allowed(error.path("param").textValue(), PARAMETERS);
        String message = error.path("message").asText("").toLowerCase(Locale.ROOT);
        HttpHeaders headers = exception.getResponseHeaders();
        String id = headers == null ? null : headers.getFirst("X-Request-Id");
        if (id != null && !REQUEST_ID.matcher(id).matches()) id = null;

        int status = exception.getStatusCode().value();
        boolean jsonMode = NarrativeProviderConfiguration.JSON_OBJECT_FORMAT.equals(format);
        if (status == 400 && jsonMode && message.contains("must contain the word")
                && (message.contains("'json'") || message.contains("\"json\"") || message.contains("`json`"))
                && message.contains("json_object")) {
            return new UpstreamModelError("json_mode_prompt_missing_keyword", "upstream_error", code, parameter, id);
        }
        if (code != null && !List.of("invalid_request_error", "server_error", "invalid_parameter").contains(code)) {
            return new UpstreamModelError(code, "upstream_error", code, parameter, id);
        }
        if (status == 429) {
            return new UpstreamModelError("rate_limited", "http_status", code, parameter, id);
        }
        if (status == 400 && jsonMode && (parameter == null || "response_format".equals(parameter) || "messages".equals(parameter))
                && !NarrativeProviderConfiguration.mentionsJson(system) && !NarrativeProviderConfiguration.mentionsJson(user)) {
            return new UpstreamModelError("json_mode_prompt_missing_keyword", "request_inspection", code, parameter, id);
        }
        return new UpstreamModelError("unclassified_upstream_error", "unknown", code, parameter, id);
    }

    /** 读取有限大小的供应商标准错误对象，其他格式使用空节点。
     * @param mapper JSON 映射器
     * @param body 只用于内存分类的上游响应字节
     */
    private static JsonNode errorObject(ObjectMapper mapper, byte[] body) {
        if (body.length > 0 && body.length <= MAX_ERROR_BODY_BYTES) {
            try {
                JsonNode root = mapper.readTree(body);
                if (root != null && root.path("error").isObject()) return root.path("error");
            } catch (IOException ignored) {
                // 代理可能返回 HTML 或文本，此时仍可根据状态和请求做诊断。
            }
        }
        return mapper.createObjectNode();
    }

    /** 仅保留代码中预先登记的供应商字段值。
     * @param value 上游原始字段，可为空
     * @param values 允许记录的固定值
     */
    private static String allowed(String value, List<String> values) {
        return value != null && values.contains(value) ? value : null;
    }

}
