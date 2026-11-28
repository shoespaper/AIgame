package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** 每次请求读取运行时配置并调用外部模型的叙事提供器。 */
public final class RuntimeNarrativeProvider {
    private static final HttpClient TURN_HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final RestClient.Builder builder;
    private final ObjectMapper mapper;
    private final NarrativeRuntimeSettingsService settings;

    /** 创建运行时模型提供器。
     * @param builder HTTP 客户端构造器，调用时复制
     * @param mapper JSON 映射器
     * @param settings 运行时模型配置服务
     */
    public RuntimeNarrativeProvider(RestClient.Builder builder, ObjectMapper mapper, NarrativeRuntimeSettingsService settings) {
        this.builder = builder;
        this.mapper = mapper;
        this.settings = settings;
    }

    /** 使用当前配置执行一次结构化叙事请求。
     * @param prompt 本次叙事提示
     */
    public NarrativeResponse respond(NarrativePrompt prompt) {
        return provider(prompt.singleAttempt()).respond(prompt);
    }

    /** 使用当前配置请求调用方定义的 JSON 对象。
     * @param operation 用于诊断的固定业务阶段标识
     * @param instruction 约定输出格式的系统提示词
     * @param input 已序列化的用户消息
     */
    public JsonNode completeJson(String operation, String instruction, String input) {
        return provider(false).completeJson(operation, instruction, input);
    }

    /** 执行带单次超时的 JSON 请求，业务流程负责重试。
     * @param operation 模型日志阶段
     * @param instruction JSON 生成约定
     * @param input 已编码的玩家上下文
     */
    public JsonNode completeJsonOnce(String operation, String instruction, String input) {
        return provider(true).completeJsonOnce(operation, instruction, input);
    }

    /** 根据当前设置创建模型适配器。
     * @param boundedTurn 是否使用游玩回合的单次请求超时
     */
    private OpenAiCompatibleNarrativeProvider provider(boolean boundedTurn) {
        NarrativeRuntimeSettingsService.Settings value = settings.read();
        if (!value.enabled() || value.apiKey().isBlank()) {
            throw new GameRequestException(GameRequestException.Code.MODEL_CONFIGURATION_REQUIRED, "Narrative API key is required");
        }
        RestClient.Builder clientBuilder = builder.clone();
        if (boundedTurn) {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(TURN_HTTP_CLIENT);
            factory.setReadTimeout(Duration.ofSeconds(90));
            clientBuilder.requestFactory(factory);
        }
        RestClient client = clientBuilder.baseUrl(value.baseUrl().isBlank() ? "https://api.openai.com/v1" : value.baseUrl())
                .defaultHeader("Authorization", "Bearer " + value.apiKey()).defaultHeader("Content-Type", "application/json").build();
        return new OpenAiCompatibleNarrativeProvider(client, mapper, value.model().isBlank() ? "gpt-4o-mini" : value.model(),
                value.maxTokens());
    }
}
