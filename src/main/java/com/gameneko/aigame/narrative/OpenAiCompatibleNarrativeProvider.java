package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.LinkedHashMap;

/** OpenAI Chat Completions 兼容服务的结构化叙事适配器。 */
public final class OpenAiCompatibleNarrativeProvider implements NarrativeProvider {
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
     * 请求严格结构化叙事结果，并转换为内部响应协议。
     * @param prompt 已确认快照、用户输入及叙事约束组成的提示
     */
    @Override
    public NarrativeResponse respond(NarrativePrompt prompt) {
        try {
            String userContent = objectMapper.writeValueAsString(new UserInput(prompt.userInput(), prompt.locale(), prompt.snapshot()));
            var request = new ChatRequest(model, List.of(new ChatMessage("system", prompt.systemInstruction()),
                    new ChatMessage("user", userContent)), 900,
                    NarrativeProviderConfiguration.jsonSchemaResponseFormat(objectMapper));
            ChatResponse response = client.post().uri("/chat/completions").body(request).retrieve().body(ChatResponse.class);
            if (response == null || response.choices() == null || response.choices().isEmpty()
                    || response.choices().getFirst().message() == null) throw new IllegalStateException("叙事服务未返回有效候选结果");
            Choice choice = response.choices().getFirst();
            if (!"stop".equals(choice.finish_reason())) throw new IllegalStateException("叙事服务未完整生成结构化结果");
            if (choice.message().refusal() != null && !choice.message().refusal().isBlank()) {
                throw new IllegalStateException("叙事服务拒绝了本次结构化请求");
            }
            String content = choice.message().content();
            if (content == null || content.isBlank()) throw new IllegalStateException("Narrative provider returned empty content");
            return toResponse(readStrict(content));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to encode or decode narrative completion", exception);
        }
    }

    /** 使用重复键检测和未知属性失败模式读取模型 JSON。 */
    /** @param content 模型返回的 JSON 文本 */
    WireResponse readStrict(String content) throws java.io.IOException {
        try (var parser = objectMapper.getFactory().createParser(content)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = objectMapper.readTree(parser);
            if (tree == null || parser.nextToken() != null) throw new IllegalArgumentException("叙事 JSON 必须且只能包含一个根对象");
            return objectMapper.readerFor(WireResponse.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(tree);
        }
    }

    /**
     * 将严格输出 DTO 转换为应用内部的提案和行动选项协议。
     * @param wire 符合服务端 JSON Schema 的模型响应 DTO
     */
    private static NarrativeResponse toResponse(WireResponse wire) {
        if (wire == null) throw new IllegalStateException("叙事响应为空");
        var proposals = wire.proposals().stream().map(proposal -> {
            var fields = new LinkedHashMap<String, String>();
            for (var field : proposal.fields()) {
                if (fields.putIfAbsent(field.name(), field.value()) != null) {
                    throw new IllegalArgumentException("叙事提案字段重复: " + field.name());
                }
            }
            return new NarrativeProposalCandidate(proposal.type(), fields);
        }).toList();
        var options = wire.options().stream().map(option -> new NarrativeOption(option.id(), option.label(),
                option.intent() == null ? null : new com.gameneko.aigame.engine.ActionIntent(option.intent().type(),
                        option.intent().target(), option.intent().arguments().stream()
                        .map(argument -> new com.gameneko.aigame.engine.ActionIntent.Argument(argument.name(), argument.value())).toList())))
                .toList();
        return new NarrativeResponse(wire.narration(), wire.mediaRequests(), proposals, options,
                wire.initializationQuestions(), wire.parseCandidates(), com.gameneko.aigame.engine.ProtocolVersion.current());
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
    /** 严格结构化输出的根 DTO。 */
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
