package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import com.gameneko.aigame.application.port.ModelJsonProvider;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.logging.DiagnosticLog;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.gameneko.aigame.engine.InitializationValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** OpenAI Chat Completions 兼容服务的结构化叙事适配器，带瞬时错误重试、截断自动扩容与明确失败。 */
public final class OpenAiCompatibleNarrativeProvider implements NarrativeProvider, ModelJsonProvider {
    private static final int MAX_ATTEMPTS = 3;
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxTokens;

    /**
     * 使用运行时默认输出上限创建叙事提供器。
     * @param client 已配置鉴权与服务地址的 HTTP 客户端
     * @param objectMapper 用于编码请求和解码响应的 JSON 映射器
     * @param model 外部叙事服务使用的模型标识
     */
    public OpenAiCompatibleNarrativeProvider(RestClient client, ObjectMapper objectMapper, String model) {
        this(client, objectMapper, model, NarrativeRuntimeSettingsService.DEFAULT_MAX_TOKENS);
    }

    /**
     * 创建使用指定模型、输出上限和 HTTP 客户端的叙事提供器。
     * @param client 已配置鉴权与服务地址的 HTTP 客户端
     * @param objectMapper 用于编码请求和解码响应的 JSON 映射器
     * @param model 外部叙事服务使用的模型标识
     * @param maxTokens 首轮请求的输出 token 上限，输出被截断时按倍数扩容
     */
    public OpenAiCompatibleNarrativeProvider(RestClient client, ObjectMapper objectMapper, String model, int maxTokens) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxTokens = Math.max(NarrativeRuntimeSettingsService.MIN_ALLOWED_MAX_TOKENS, maxTokens);
    }

    /**
     * 请求结构化叙事结果，记录每次重试和最终结果。
     * @param prompt 已确认快照、用户输入及叙事约束组成的提示
     */
    @Override
    public NarrativeResponse respond(NarrativePrompt prompt) {
        return complete(call -> {
            String system = prompt.systemInstruction() + "\n\n" + NarrativeProviderConfiguration.SCHEMA_DESCRIPTION;
            String user;
            try {
                user = objectMapper.writeValueAsString(new UserInput(prompt.userInput(), prompt.locale(), prompt.snapshot(), prompt.history()));
            } catch (IOException exception) {
                call.systemMessage = system;
                throw call.failure("无法编码叙事请求", false, exception, "request_encoding_failed");
            }
            return toResponse(readStrict(request(system, user, call)));
        }, result -> result.action() == null ? null : result.action().type());
    }

    /** 使用独立提示词请求 JSON 对象，并沿用模型重试和日志。
     * @param instruction 约定输出格式的系统提示词
     * @param input 已序列化的用户消息
     */
    @Override
    public JsonNode completeJson(String instruction, String input) {
        return complete(call -> readObject(request(instruction, input, call)), result -> null);
    }

    /** 执行模型请求、解析响应并逐轮记录诊断。
     * @param operation 读取并解析单次调用的操作
     * @param actionType 可从业务结果提取的动作类型
     * @param <T> 业务响应类型
     */
    private <T> T complete(ModelOperation<T> operation, Function<T, String> actionType) {
        int budget = maxTokens;
        for (int attempt = 1; ; attempt++) {
            CallAttempt call = new CallAttempt(attempt, budget, System.nanoTime());
            try {
                T result;
                try {
                    result = operation.run(call);
                } catch (IOException | IllegalArgumentException exception) {
                    throw call.failure("模型返回的内容无法解析", false, exception, "output_parse_failed");
                }
                DiagnosticLog.modelAttempt(metrics(call, actionType.apply(result)), "success", null, null);
                return result;
            } catch (NarrativeProviderException exception) {
                String reason = exception.reasonCode();
                boolean truncated = "output_truncated".equals(reason);
                int nextBudget = truncated
                        ? Math.min(budget * 2, NarrativeRuntimeSettingsService.MAX_ALLOWED_MAX_TOKENS) : budget;
                if (attempt < MAX_ATTEMPTS && (exception.retryable() || truncated && nextBudget > budget)) {
                    long delay = truncated ? 0 : backoffMillis(attempt);
                    DiagnosticLog.modelAttempt(metrics(call, null), "retry", reason, delay);
                    if (truncated) DiagnosticLog.modelBudgetChanged(budget, nextBudget);
                    budget = nextBudget;
                    if (delay > 0) sleep(delay);
                    continue;
                }
                DiagnosticLog.modelAttempt(metrics(call, null), "failed", reason, null);
                String message = exception.getMessage();
                if (truncated) {
                    message = "模型输出达到 " + budget + " tokens 上限仍被截断：" + message
                            + "，请提高 maxTokens 配置或缩短叙事提示";
                } else if (exception.retryable() && attempt > 1) {
                    message += "（已重试 " + (attempt - 1) + " 次）";
                }
                throw new NarrativeProviderException(message, exception.retryable(), exception, reason, call.status);
            }
        }
    }

    /** 发送模型请求并返回模型回复原文。
     * @param system 系统提示词
     * @param user 模型实际收到的用户消息
     * @param call 本次调用的诊断数据与输出 token 预算
     */
    private String request(String system, String user, CallAttempt call) {
        call.systemMessage = system;
        call.userMessage = user;
        ChatRequest request = new ChatRequest(model, List.of(
                new ChatMessage("system", call.systemMessage), new ChatMessage("user", call.userMessage)), call.budget,
                NarrativeProviderConfiguration.jsonObjectResponseFormat(objectMapper));
        ChatResponse response;
        long upstreamStarted = System.nanoTime();
        try {
            ResponseEntity<ChatResponse> upstream = client.post().uri("/chat/completions").body(request)
                    .retrieve().toEntity(ChatResponse.class);
            call.status = upstream.getStatusCode().value();
            response = upstream.getBody();
        } catch (RestClientResponseException exception) {
            call.status = exception.getStatusCode().value();
            boolean retryable = call.status == 429 || call.status >= 500;
            throw call.failure("模型服务返回 HTTP " + call.status, retryable, exception,
                    call.status == 429 ? "rate_limited" : "upstream_http_error");
        } catch (ResourceAccessException exception) {
            Throwable cause = exception.getMostSpecificCause();
            boolean timeout = cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException;
            throw call.failure("无法连接模型服务（连接超时或网络不可用）", true, exception,
                    timeout ? "timeout" : "connection_failed");
        } catch (RestClientException exception) {
            throw call.failure("无法解析模型服务响应", false, exception, "response_decoding_failed");
        } finally {
            DiagnosticLog.modelUpstream(call.number, call.status,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - upstreamStarted));
        }
        if (response != null) call.usage = response.usage();
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst() == null || response.choices().getFirst().message() == null) {
            throw call.failure("模型服务未返回有效候选结果", false, null, "missing_choice");
        }
        Choice choice = response.choices().getFirst();
        call.modelResponse = choice.message().content();
        call.modelRefusal = choice.message().refusal();
        if ("length".equals(choice.finish_reason())) {
            throw call.failure("finish_reason=length，本次请求 max_tokens=" + call.budget
                    + "，服务端实际输出 " + completionTokens(response) + " tokens", false, null, "output_truncated");
        }
        if (!"stop".equals(choice.finish_reason())) {
            throw call.failure("模型未完整生成结果", false, null, "incomplete_output");
        }
        if (call.modelRefusal != null && !call.modelRefusal.isBlank()) {
            throw call.failure("模型拒绝了本次请求", false, null, "model_refusal");
        }
        String content = call.modelResponse;
        if (content == null || content.isBlank()) throw call.failure("模型返回了空内容", false, null, "empty_output");
        return content;
    }

    /** 读取单次调用的业务结果。
     * @param <T> 业务响应类型
     */
    @FunctionalInterface
    private interface ModelOperation<T> {
        /** 执行请求并解析响应。
         * @param call 当前尝试的诊断数据
         */
        T run(CallAttempt call) throws IOException;
    }

    /** 提取本次尝试的指标以及发往模型的消息和收到的原文。
     * @param call 单次模型尝试的状态和对话内容
     * @param actionType 可空的模型提议动作类型
     */
    private DiagnosticLog.ModelAttempt metrics(CallAttempt call, String actionType) {
        Usage usage = call.usage;
        return new DiagnosticLog.ModelAttempt(model, call.number, call.status, call.budget,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - call.started),
                usage == null ? null : usage.prompt_tokens(), usage == null ? null : usage.completion_tokens(), actionType,
                call.systemMessage, call.userMessage, call.modelResponse, call.modelRefusal);
    }

    /** 解析模型 JSON：剥离代码围栏并截取首个对象，容忍未知字段但拒绝重复键。 */
    /** @param content 模型返回的文本 */
    WireResponse readStrict(String content) throws IOException {
        return objectMapper.readerFor(WireResponse.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(readObject(content));
    }

    /** 读取单个 JSON 对象并拒绝重复键。
     * @param content 模型返回的原始文本
     */
    private JsonNode readObject(String content) throws IOException {
        try (JsonParser parser = objectMapper.getFactory().createParser(extractJson(content))) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = objectMapper.readTree(parser);
            if (tree == null || !tree.isObject() || parser.nextToken() != null) {
                throw new IllegalArgumentException("叙事 JSON 必须且只能包含一个根对象");
            }
            return tree;
        }
    }

    /** 从模型文本中提取 JSON：剥离 markdown 代码围栏并截取首个对象。 */
    /** @param content 原始文本 */
    private static String extractJson(String content) {
        String text = content == null ? "" : content.trim();
        if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) {
                text = text.substring(newline + 1);
                int fence = text.lastIndexOf("```");
                if (fence >= 0) text = text.substring(0, fence);
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) return text.trim();
        return text.substring(start, end + 1).trim();
    }

    /**
     * 读取响应中的实际输出 token 数，服务端未返回用量时以未知标记代替。
     * @param response 模型返回的完整响应体
     * @return 实际输出 token 数的可读文本
     */
    private static String completionTokens(ChatResponse response) {
        return response.usage() == null || response.usage().completion_tokens() == null
                ? "未知" : String.valueOf(response.usage().completion_tokens());
    }

    /** 计算第 attempt 次尝试后的退避毫秒数。 */
    /** @param attempt 当前尝试次数（从 1 开始） */
    private static long backoffMillis(int attempt) {
        return 500L * (1L << (attempt - 1));
    }

    /** 阻塞等待退避时间，中断时恢复中断标记。 */
    /** @param millis 等待毫秒数 */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 将模型输出 DTO 转换为应用内部的叙事与引擎调用协议，并对缺失的集合字段做空值兜底。
     * @param wire 符合响应结构的模型响应 DTO
     */
    private static NarrativeResponse toResponse(WireResponse wire) {
        if (wire == null) throw new IllegalArgumentException("叙事响应为空");
        ActionIntent action = wire.action() == null ? null : new ActionIntent(wire.action().type(),
                wire.action().target(), arguments(wire.action()));
        return new NarrativeResponse(wire.narration(), action, wire.options(), wire.updates());
    }

    /** 将意图中的参数数组转换为引擎中立参数，缺失时返回空列表。 */
    /** @param intent 模型返回的行动意图 */
    private static List<ActionIntent.Argument> arguments(IntentWire intent) {
        if (intent == null || intent.arguments() == null) return List.of();
        return intent.arguments().stream().map(argument -> new ActionIntent.Argument(argument.name(), argument.value())).toList();
    }

    /** 保存一次模型调用的预算、计时与可用的上游元数据。 */
    private static final class CallAttempt {
        private final int number;
        private final int budget;
        private final long started;
        private Integer status;
        private Usage usage;
        private String systemMessage;
        private String userMessage;
        private String modelResponse;
        private String modelRefusal;

        /** 创建一次模型调用的诊断状态。
         * @param number 从 1 开始的调用次数
         * @param budget 本次调用的输出 token 上限
         * @param started 本次调用开始时的单调时钟纳秒数
         */
        private CallAttempt(int number, int budget, long started) {
            this.number = number;
            this.budget = budget;
            this.started = started;
        }

        /** 创建携带本次调用状态的叙事异常。
         * @param message 用户可读的错误提示
         * @param retryable 是否允许退避重试
         * @param cause 底层异常，可为空
         * @param reason 稳定的失败原因码
         */
        private NarrativeProviderException failure(String message, boolean retryable, Throwable cause, String reason) {
            return new NarrativeProviderException(message, retryable, cause, reason, status);
        }
    }

    /** 模型调用中的用户文本与只读世界快照。 */
    private record UserInput(String playerInput, String locale, NarrativeSnapshot confirmedSnapshot,
                             List<NarrativeRequest.ConversationMessage> history) {}
    /** Chat Completions 请求体。 */
    private record ChatRequest(String model, List<ChatMessage> messages, int max_tokens, JsonNode response_format) {}
    /** Chat Completions 消息。 */
    private record ChatMessage(String role, String content) {}
    /** Chat Completions 响应体。 */
    private record ChatResponse(List<Choice> choices, Usage usage) {}
    /** 服务端返回的 token 用量。 */
    private record Usage(Integer prompt_tokens, Integer completion_tokens) {}
    /** 模型返回的候选结果。 */
    private record Choice(CompletionMessage message, String finish_reason) {}
    /** 外部服务返回的消息内容和拒绝标记。 */
    private record CompletionMessage(String content, String refusal) {}
    /** 结构化输出的根 DTO。 */
    record WireResponse(String narration, IntentWire action, List<String> options,
                        Map<String, InitializationValue> updates) {}
    /** 引擎中立的行动意图。 */
    private record IntentWire(String type, String target, List<ArgumentWire> arguments) {}
    /** 行动意图中的类型化参数。 */
    private record ArgumentWire(String name, InitializationValue value) {}
}
