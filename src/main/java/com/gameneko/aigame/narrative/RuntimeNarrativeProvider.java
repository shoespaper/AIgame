package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import com.gameneko.aigame.application.port.NarrativeProvider;
import org.springframework.web.client.RestClient;

/** 每次请求读取运行时配置并调用外部模型的叙事提供器。 */
public final class RuntimeNarrativeProvider implements NarrativeProvider {
    private final RestClient.Builder builder; private final ObjectMapper mapper; private final NarrativeRuntimeSettingsService settings;
    /** 创建运行时提供器。 @param builder HTTP 客户端构造器 @param mapper JSON 映射器 @param settings 运行时配置服务 */
    public RuntimeNarrativeProvider(RestClient.Builder builder, ObjectMapper mapper, NarrativeRuntimeSettingsService settings) { this.builder = builder; this.mapper = mapper; this.settings = settings; }
    /** 使用当前配置执行一次结构化叙事请求。 @param prompt 叙事提示 */
    @Override public NarrativeResponse respond(NarrativePrompt prompt) { var value = settings.read(); if (!value.enabled() || value.apiKey().isBlank()) throw new IllegalStateException("Narrative API key is required"); var client = builder.baseUrl(value.baseUrl().isBlank() ? "https://api.openai.com/v1" : value.baseUrl()).defaultHeader("Authorization", "Bearer " + value.apiKey()).defaultHeader("Content-Type", "application/json").build(); return new OpenAiCompatibleNarrativeProvider(client, mapper, value.model().isBlank() ? "gpt-4o-mini" : value.model(), value.maxTokens()).respond(prompt); }
}
