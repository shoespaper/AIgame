package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.LinkedHashMap;

/** OpenAI Chat Completions 兼容服务的结构化叙事适配器，带瞬时错误重试与明确失败。 */
public final class OpenAiCompatibleNarrativeProvider implements NarrativeProvider {
    private static final int MAX_ATTEMPTS = 3;
    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String model;

    /**
     * 创建使用指定模型和 HTTP 客户端的叙事提供器。
     * @param client 已配置鉴权与服务地址的 HTTP 客户端
     * @param objectMapper 用于编码请求和解码响应的 JSON 映射器
     * @param model 外部叙事服务使用的模型标识
     */
    public OpenAiCompatibleNarrativeProvider(RestClient client, ObjectMapper objectMapper, String model) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.model = model;
    }

    /**
     * 请求结构化叙事结果，对瞬时错误重试，最终失败抛出明确异常。
     * @param prompt 已确认快照、用户输入及叙事约束组成的提示
     */
    @Override
    public NarrativeResponse respond(NarrativePrompt prompt) {
        NarrativeProviderException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doRespond(prompt);
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

    /** 执行一次模型调用并解析响应，将各类失败统一转换为可读异常。 */
    /** @param prompt 叙事提示 */
    private NarrativeResponse doRespond(NarrativePrompt prompt) {
        String userContent;
        try {
            userContent = objectMapper.writeValueAsString(new UserInput(prompt.userInput(), prompt.locale(), prompt.snapshot()));
        } catch (java.io.IOException exception) {
            throw new NarrativeProviderException("无法编码叙事请求", false, exception);
        }
        var request = new ChatRequest(model, List.of(
                new ChatMessage("system", prompt.systemInstruction() + "\n\n" + NarrativeProviderConfiguration.SCHEMA_DESCRIPTION),
                new ChatMessage("user", userContent)), 900,
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
     * 将模型输出 DTO 转换为应用内部的提案和行动选项协议，并对可能缺失的数组字段做空值兜底。
     * @param wire 符合响应结构的模型响应 DTO
     */
    private static NarrativeResponse toResponse(WireResponse wire) {
        if (wire == null) throw new IllegalArgumentException("叙事响应为空");
        var proposals = (wire.proposals() == null ? List.<ProposalWire>of() : wire.proposals()).stream().map(proposal -> {
            var fields = new LinkedHashMap<String, String>();
            if (proposal.fields() != null) for (var field : proposal.fields()) {
                if (fields.putIfAbsent(field.name(), field.value()) != null) {
                    throw new IllegalArgumentException("叙事提案字段重复: " + field.name());
                }
            }
            return new NarrativeProposalCandidate(proposal.type(), fields);
        }).toList();
        var options = (wire.options() == null ? List.<OptionWire>of() : wire.options()).stream().map(option -> new NarrativeOption(option.id(), option.label(),
                option.intent() == null ? null : new com.gameneko.aigame.engine.ActionIntent(option.intent().type(),
                        option.intent().target(), arguments(option.intent()))))
                .toList();
        return new NarrativeResponse(wire.narration(), wire.mediaRequests(), proposals, options,
                wire.initializationQuestions(), wire.parseCandidates(), com.gameneko.aigame.engine.ProtocolVersion.current());
    }

    /** 将意图中的参数数组转换为引擎中立参数，缺失时返回空列表。 */
    /** @param intent 模型返回的行动意图 */
    private static List<com.gameneko.aigame.engine.ActionIntent.Argument> arguments(IntentWire intent) {
        if (intent == null || intent.arguments() == null) return List.of();
        return intent.arguments().stream().map(argument -> new com.gameneko.aigame.engine.ActionIntent.Argument(argument.name(), argument.value())).toList();
    }

    /** 模型调用中的用户文本与只读世界快照。 */
    private record UserInput(String playerInput, String locale, NarrativeSnapshot confirmedSnapshot) {}
    /** Chat Completions 请求体。 */
    private record ChatRequest(String model, List<ChatMessage> messages, int max_tokens, JsonNode response_format) {}
    /** Chat Completions 消息。 */
    private record ChatMessage(String role, String content) {}
    /** Chat Completions 响应体。 */
    private record ChatResponse(List<Choice> choices) {}
    /** 模型返回的候选结果。 */
    private record Choice(CompletionMessage message, String finish_reason) {}
    /** 外部服务返回的消息内容和拒绝标记。 */
    private record CompletionMessage(String content, String refusal) {}
    /** 结构化输出的根 DTO。 */
    record WireResponse(String narration, List<NarrativeMediaRequest> mediaRequests,
                        List<ProposalWire> proposals, List<OptionWire> options,
                        List<NarrativeInitializationQuestion> initializationQuestions,
                        List<NarrativeParseCandidate> parseCandidates) {}
    /** 使用键值对数组表达的动态提案字段。 */
    private record ProposalWire(String type, List<ProposalField> fields) {}
    /** 单个结构化提案字段。 */
    private record ProposalField(String name, String value) {}
    /** GM 提供的行动选择及其通用意图。 */
    private record OptionWire(String id, String label, IntentWire intent) {}
    /** 引擎中立的行动意图。 */
    private record IntentWire(String type, String target, List<ArgumentWire> arguments) {}
    /** 行动意图中的类型化参数。 */
    private record ArgumentWire(String name, com.gameneko.aigame.engine.InitializationValue value) {}
}
