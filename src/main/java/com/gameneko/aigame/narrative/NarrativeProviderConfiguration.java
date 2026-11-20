package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 叙事提供器的 Spring 装配配置，支持本地回退与外部结构化模型。 */
@Configuration(proxyBeanMethods = false)
public class NarrativeProviderConfiguration {
    /** 返回确定性本地叙事提供器作为默认实现。 */
    @Bean
    @ConditionalOnProperty(prefix = "aigame.narrative", name = "enabled", havingValue = "false", matchIfMissing = true)
    NarrativeProvider localNarrativePort() {
        LocalNarrativeProvider fallback = new LocalNarrativeProvider();
        return fallback::respond;
    }

    /**
     * 创建配置鉴权、超时和严格 JSON Schema 输出格式的外部模型提供器。
     * @param restClientBuilder Spring 提供的 HTTP 客户端构造器
     * @param objectMapper 项目共享的 JSON 编解码器
     * @param apiKey 外部叙事服务的访问密钥
     * @param baseUrl 兼容 Chat Completions 的服务基础地址
     * @param model 外部服务使用的模型名称
     */
    @Bean
    @ConditionalOnProperty(prefix = "aigame.narrative", name = "enabled", havingValue = "true")
    NarrativeProvider openAiCompatibleNarrativePort(RestClient.Builder restClientBuilder,
                                                    ObjectMapper objectMapper,
                                                    @Value("${aigame.narrative.api-key}") String apiKey,
                                                    @Value("${aigame.narrative.base-url:https://api.openai.com/v1}") String baseUrl,
                                                    @Value("${aigame.narrative.model:gpt-4o-mini}") String model) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Narrative API key is required when the external provider is enabled");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(3));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(20));
        RestClient client = restClientBuilder.requestFactory(requestFactory).baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
        return new OpenAiCompatibleNarrativeProvider(client, objectMapper, model);
    }

    /**
     * 构造要求模型严格遵循的叙事响应 JSON Schema 格式。
     * @param objectMapper 用于解析内置 JSON Schema 的映射器
     */
    static ObjectNode jsonSchemaResponseFormat(ObjectMapper objectMapper) {
        String schema = """
                {
                  "type": "json_schema",
                  "json_schema": {
                    "name": "narrative_response",
                    "strict": true,
                    "schema": {
                      "type": "object",
                      "additionalProperties": false,
                      "properties": {
                        "narration": {"type": "string"},
                        "mediaRequests": {"type": "array", "items": {
                          "type": "object", "additionalProperties": false,
                          "properties": {"kind": {"type": "string"}, "prompt": {"type": "string"}, "assetId": {"type": "string"}},
                          "required": ["kind", "prompt", "assetId"]}},
                        "proposals": {"type": "array", "items": {
                          "type": "object", "additionalProperties": false,
                          "properties": {"type": {"type": "string"}, "fields": {"type": "array", "items": {
                            "type": "object", "additionalProperties": false,
                            "properties": {"name": {"type": "string"}, "value": {"type": "string"}},
                            "required": ["name", "value"]}}},
                          "required": ["type", "fields"]}},
                        "options": {"type": "array", "items": {
                          "type": "object", "additionalProperties": false,
                          "properties": {"id": {"type": "string"}, "label": {"type": "string"}, "intent": {"anyOf": [
                            {"type": "object", "additionalProperties": false,
                             "properties": {"type": {"type": "string"}, "target": {"type": "string"}, "arguments": {"type": "array", "items": {
                               "type": "object", "additionalProperties": false,
                               "properties": {"name": {"type": "string"}, "value": {"anyOf": [
                                 {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["text"]}, "value": {"type": "string"}}, "required": ["type", "value"]},
                                 {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["integer"]}, "value": {"type": "integer"}}, "required": ["type", "value"]},
                                 {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["decimal"]}, "value": {"type": "number"}}, "required": ["type", "value"]},
                                 {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["flag"]}, "value": {"type": "boolean"}}, "required": ["type", "value"]}
                               ]}},
                               "required": ["name", "value"]}},
                             "required": ["type", "target", "arguments"]},
                            {"type": "null"}] }},
                          "required": ["id", "label", "intent"]}},
                        "initializationQuestions": {"type": "array", "items": {
                          "type": "object", "additionalProperties": false,
                          "properties": {"fieldId": {"type": "string"}, "prompt": {"type": "string"}, "options": {"type": "array", "items": {"type": "string"}}},
                          "required": ["fieldId", "prompt", "options"]}},
                        "parseCandidates": {"type": "array", "items": {
                          "type": "object", "additionalProperties": false,
                          "properties": {"fieldId": {"type": "string"}, "value": {"anyOf": [
                            {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["text"]}, "value": {"type": "string"}}, "required": ["type", "value"]},
                            {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["integer"]}, "value": {"type": "integer"}}, "required": ["type", "value"]},
                            {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["decimal"]}, "value": {"type": "number"}}, "required": ["type", "value"]},
                            {"type": "object", "additionalProperties": false, "properties": {"type": {"type": "string", "enum": ["flag"]}, "value": {"type": "boolean"}}, "required": ["type", "value"]}
                          ]}, "confidence": {"type": "number"}},
                          "required": ["fieldId", "value", "confidence"]}}
                      },
                      "required": ["narration", "mediaRequests", "proposals", "options", "initializationQuestions", "parseCandidates"]
                    }
                  }
                }
                """;
        try {
            return (ObjectNode) objectMapper.readTree(schema);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("内置叙事 JSON Schema 无法解析", exception);
        }
    }
}
