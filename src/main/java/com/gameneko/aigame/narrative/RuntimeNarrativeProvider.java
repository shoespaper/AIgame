package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import org.springframework.web.client.RestClient;

/** 每次请求读取运行时配置并调用外部模型的叙事提供器。 */
public final class RuntimeNarrativeProvider {
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
        return provider().respond(prompt);
    }

    /** 使用当前配置请求调用方定义的 JSON 对象。
     * @param instruction 约定输出格式的系统提示词
     * @param input 已序列化的用户消息
     */
    public JsonNode completeJson(String instruction, String input) {
        return provider().completeJson(instruction, input);
    }

    /** 根据当前设置创建模型适配器。 */
    private OpenAiCompatibleNarrativeProvider provider() {
        NarrativeRuntimeSettingsService.Settings value = settings.read();
        if (!value.enabled() || value.apiKey().isBlank()) {
            throw new GameRequestException(GameRequestException.Code.MODEL_CONFIGURATION_REQUIRED, "Narrative API key is required");
        }
        RestClient client = builder.clone().baseUrl(value.baseUrl().isBlank() ? "https://api.openai.com/v1" : value.baseUrl())
                .defaultHeader("Authorization", "Bearer " + value.apiKey()).defaultHeader("Content-Type", "application/json").build();
        return new OpenAiCompatibleNarrativeProvider(client, mapper, value.model().isBlank() ? "gpt-4o-mini" : value.model(),
                value.maxTokens());
    }
}
