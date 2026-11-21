package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 叙事提供器的 Spring 装配配置，支持本地回退与外部结构化模型。 */
@Configuration(proxyBeanMethods = false)
public class NarrativeProviderConfiguration {
    /** 返回确定性本地叙事提供器作为默认实现。 */
    @Bean
    NarrativeProvider runtimeNarrativePort(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                                           NarrativeRuntimeSettingsService settings) {
        return new RuntimeNarrativeProvider(restClientBuilder, objectMapper, settings);
    }

    /** 期望模型输出的叙事 JSON 结构描述，供 json_object 模式下模型对齐字段。 */
    static final String SCHEMA_DESCRIPTION = """
            Respond with a single JSON object containing exactly these six fields (all required; use [] when empty):
            1. "narration": a string of narrative text.
            2. "mediaRequests": an array of {"kind": string, "prompt": string, "assetId": string}.
            3. "proposals": an array of {"type": string, "fields": [{"name": string, "value": string}]}.
            4. "options": an array of {"id": string, "label": string, "intent": {"type": string, "target": string|null, "arguments": [{"name": string, "value": {"type": "text"|"integer"|"decimal"|"flag", "value": ...}}]} | null}.
            5. "initializationQuestions": an array of {"fieldId": string, "prompt": string, "options": [string]}.
            6. "parseCandidates": an array of {"fieldId": string, "value": {"type": "text"|"integer"|"decimal"|"flag", "value": ...}, "confidence": number}.
            Output only the JSON object and nothing else.
            """;

    /**
     * 构造要求模型返回 JSON 对象的响应格式，兼容 OpenAI 与 DeepSeek 等仅支持 json_object 模式的服务。
     * @param objectMapper 用于解析内置响应格式的映射器
     */
    static ObjectNode jsonObjectResponseFormat(ObjectMapper objectMapper) {
        try {
            return (ObjectNode) objectMapper.readTree("{\"type\": \"json_object\"}");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("内置叙事 JSON 响应格式无法解析", exception);
        }
    }
}
