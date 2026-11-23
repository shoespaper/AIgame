package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.ActionIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

/** OpenAI Chat Completions 兼容服务的结构化叙事适配器，带瞬时错误重试、截断自动扩容与明确失败。 */
public final class OpenAiCompatibleNarrativeProvider implements NarrativeProvider {
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
     * 请求结构化叙事结果，对瞬时错误重试，输出被截断时提高预算重试，最终失败抛出明确异常。
     * @param prompt 已确认快照、用户输入及叙事约束组成的提示
     */
    @Override
    public NarrativeResponse respond(NarrativePrompt prompt) {
        NarrativeProviderException failure = null;
        int budget = maxTokens;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doRespond(prompt, budget);
            } catch (TruncatedOutputException exception) {
                // 截断说明 JSON 未闭合，用相同预算重试必然再次失败，只有在预算确实还能变大时才重试
                int escalated = Math.min(budget * 2, NarrativeRuntimeSettingsService.MAX_ALLOWED_MAX_TOKENS);
                if (escalated <= budget || attempt == MAX_ATTEMPTS) {
                    throw new NarrativeProviderException("模型输出达到 " + budget + " tokens 上限仍被截断："
                            + exception.getMessage() + "，请提高 maxTokens 配置或缩短叙事提示", false, exception);
                }
                budget = escalated;
            } catch (NarrativeProviderException exception) {
                failure = exception;
                if (!exception.retryable() || attempt == MAX_ATTEMPTS) break;
                sleep(backoffMillis(attempt));
            }
        }
        String suffix = failure != null && failure.retryable() ? "（已重试 " + (MAX_ATTEMPTS - 1) + " 次）" : "";
        throw new NarrativeProviderException(failure == null ? "叙事模型调用失败" : failure.getMessage() + suffix,
                failure != null && failure.retryable(), failure);
    }

    /**
     * 执行一次模型调用并解析响应，将各类失败统一转换为可读异常。
     * @param prompt 叙事提示
     * @param outputTokens 本次请求允许的最大输出 token 数
     */
    private NarrativeResponse doRespond(NarrativePrompt prompt, int outputTokens) {
        String userContent;
        try {
        userContent = objectMapper.writeValueAsString(new UserInput(prompt.userInput(), prompt.locale(), prompt.snapshot(), prompt.history()));
        } catch (java.io.IOException exception) {
            throw new NarrativeProviderException("无法编码叙事请求", false, exception);
        }
        var request = new ChatRequest(model, List.of(
                new ChatMessage("system", prompt.systemInstruction() + "\n\n" + NarrativeProviderConfiguration.SCHEMA_DESCRIPTION),
                new ChatMessage("user", userContent)), outputTokens,
                NarrativeProviderConfiguration.jsonObjectResponseFormat(objectMapper));
        ChatResponse response;
        try {
            response = client.post().uri("/chat/completions").body(request).retrieve().body(ChatResponse.class);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean retryable = status == 429 || status >= 500;
            throw new NarrativeProviderException("模型服务返回 HTTP " + status + "：" + brief(exception), retryable, exception);
        } catch (ResourceAccessException exception) {
            throw new NarrativeProviderException("无法连接模型服务（连接超时或网络不可用）", true, exception);
        }
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new NarrativeProviderException("模型服务未返回有效候选结果", false);
        }
        Choice choice = response.choices().getFirst();
        if ("length".equals(choice.finish_reason())) {
            throw new TruncatedOutputException("finish_reason=length，本次请求 max_tokens=" + outputTokens
                    + "，服务端实际输出 " + completionTokens(response) + " tokens");
        }
        if (!"stop".equals(choice.finish_reason())) {
            throw new NarrativeProviderException("模型未完整生成结果（finish_reason=" + choice.finish_reason() + "）", false);
        }
        if (choice.message().refusal() != null && !choice.message().refusal().isBlank()) {
            throw new NarrativeProviderException("模型拒绝了本次请求", false);
        }
        String content = choice.message().content();
        if (content == null || content.isBlank()) throw new NarrativeProviderException("模型返回了空内容", false);
        try {
            return toResponse(readStrict(content));
        } catch (java.io.IOException | IllegalArgumentException exception) {
            throw new NarrativeProviderException("模型返回的内容无法解析：" + brief(exception), false, exception);
        }
    }

    /** 解析模型 JSON：剥离代码围栏并截取首个对象，容忍未知字段但拒绝重复键。 */
    /** @param content 模型返回的文本 */
    WireResponse readStrict(String content) throws java.io.IOException {
        String json = extractJson(content);
        try (var parser = objectMapper.getFactory().createParser(json)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = objectMapper.readTree(parser);
            if (tree == null || parser.nextToken() != null) throw new IllegalArgumentException("叙事 JSON 必须且只能包含一个根对象");
            return objectMapper.readerFor(WireResponse.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(tree);
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

    /** 返回异常的简短描述，避免把大段响应体暴露给前端。 */
    /** @param exception 待描述异常 */
    private static String brief(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 200 ? message.substring(0, 200) + "…" : message;
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
     * 将模型输出 DTO 转换为应用内部的叙事与引擎调用协议，并对可能缺失的数组字段做空值兜底。
     * @param wire 符合响应结构的模型响应 DTO
     */
    private static NarrativeResponse toResponse(WireResponse wire) {
        if (wire == null) throw new IllegalArgumentException("叙事响应为空");
        ActionIntent action = wire.action() == null ? null : new ActionIntent(wire.action().type(),
                wire.action().target(), arguments(wire.action()));
        return new NarrativeResponse(wire.narration(), action, wire.options(), wire.initializationQuestions(), wire.parseCandidates());
    }

    /** 将意图中的参数数组转换为引擎中立参数，缺失时返回空列表。 */
    /** @param intent 模型返回的行动意图 */
    private static List<ActionIntent.Argument> arguments(IntentWire intent) {
        if (intent == null || intent.arguments() == null) return List.of();
        return intent.arguments().stream().map(argument -> new ActionIntent.Argument(argument.name(), argument.value())).toList();
    }

    /**
     * 模型输出因子句上限被截断时的内部控制异常，仅用于触发提高预算后的重试，不会直接暴露给调用方。
     */
    private static final class TruncatedOutputException extends RuntimeException {
        /**
         * 创建截断控制异常。
         * @param message 描述本次截断的预算与实际用量
         */
        TruncatedOutputException(String message) { super(message); }
    }

    /** 模型调用中的用户文本与只读世界快照。 */
    private record UserInput(String playerInput, String locale, NarrativeSnapshot confirmedSnapshot,
                             java.util.List<NarrativeRequest.ConversationMessage> history) {}
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
                        List<NarrativeInitializationQuestion> initializationQuestions,
                        List<NarrativeParseCandidate> parseCandidates) {}
    /** 引擎中立的行动意图。 */
    private record IntentWire(String type, String target, List<ArgumentWire> arguments) {}
    /** 行动意图中的类型化参数。 */
    private record ArgumentWire(String name, com.gameneko.aigame.engine.InitializationValue value) {}
}
