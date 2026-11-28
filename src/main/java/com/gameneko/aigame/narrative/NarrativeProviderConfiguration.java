package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.application.NarrativeRuntimeSettingsService;
import com.gameneko.aigame.application.port.ModelJsonProvider;
import com.gameneko.aigame.application.port.NarrativeProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Locale;

/** 装配游玩叙事和世界构建共用的模型连接。 */
@Configuration(proxyBeanMethods = false)
public class NarrativeProviderConfiguration {
    /** 装配按运行时设置读取外部模型连接的提供器。
     * @param restClientBuilder 模型 HTTP 客户端构造器
     * @param objectMapper 模型 JSON 序列化与解析器
     * @param settings 模型运行时设置服务
     */
    @Bean
    RuntimeNarrativeProvider runtimeProvider(RestClient.Builder restClientBuilder, ObjectMapper objectMapper,
                                           NarrativeRuntimeSettingsService settings) {
        return new RuntimeNarrativeProvider(restClientBuilder, objectMapper, settings);
    }

    /** 导出游玩与角色初始化使用的叙事端口。
     * @param provider 共享的运行时模型提供器
     */
    @Bean
    NarrativeProvider runtimeNarrativePort(RuntimeNarrativeProvider provider) {
        return provider::respond;
    }

    /** 导出世界构建使用的独立 JSON 协议端口。
     * @param provider 共享的运行时模型提供器
     */
    @Bean
    ModelJsonProvider runtimeModelJsonPort(RuntimeNarrativeProvider provider) {
        return new ModelJsonProvider() {
            /** 请求 JSON 对象并沿用提供器的常规重试。
             * @param operation 模型业务阶段
             * @param instruction 输出协议
             * @param input 已编码消息
             */
            @Override
            public JsonNode completeJson(String operation, String instruction, String input) {
                return provider.completeJson(operation, instruction, input);
            }

            /** 只请求一次 JSON 对象。
             * @param operation 模型业务阶段
             * @param instruction 输出协议
             * @param input 已编码消息
             */
            @Override
            public JsonNode completeJsonOnce(String operation, String instruction, String input) {
                return provider.completeJsonOnce(operation, instruction, input);
            }
        };
    }

    static final String JSON_OBJECT_FORMAT = "json_object";

    /** 在 JSON 模式的系统消息中补齐兼容服务商要求的关键词。
     * @param instruction 原始系统提示词
     */
    static String jsonModeSystemMessage(String instruction) {
        return mentionsJson(instruction) ? instruction : instruction + "\n\n只返回一个 JSON 对象。";
    }

    /** 检查消息文本是否包含服务商识别的 JSON 关键词。
     * @param message 系统消息或用户消息，可为空
     */
    static boolean mentionsJson(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("json");
    }

    /** 期望模型输出的叙事 JSON 结构描述，供 json_object 模式下模型对齐字段。 */
    static final String SCHEMA_DESCRIPTION = """
            Respond with a single JSON object containing exactly these four fields (all required; use [] for empty options and {} for empty updates):
            1. "narration": a string of narrative text.
            2. "action": null, or an object {"type": string, "target": string|null, "arguments": [{"name": string, "value": {"type": "text"|"integer"|"decimal"|"flag", "value": ...}}]} representing one engine call. Use null for pure narration.
            3. "options": an array of short strings, each a suggested next action for the player (each at most 12 Chinese characters).
            4. "updates": an object keyed by field name, with typed values {"type": "text"|"integer"|"decimal"|"flag", "value": ...}. Declare confirmed values directly; use {} when there are no updates.
            Output length guidance and structural limits:
            - narration: normally 300 to 600 Chinese characters, including opening scenes. Important dialogue or scene transitions may extend to 1000.
            - action: at most 3 arguments.
            - options: at most 4 items.
            - updates: at most 16 fields.
            Start the reply immediately with `{"narration":` and end with `}`. Output only the JSON object, with no Markdown or leading whitespace.
            """;

    /**
     * 构造要求模型返回 JSON 对象的响应格式，兼容 OpenAI 与 DeepSeek 等仅支持 json_object 模式的服务。
     * @param objectMapper 用于解析内置响应格式的映射器
     */
    static ObjectNode jsonObjectResponseFormat(ObjectMapper objectMapper) {
        return objectMapper.createObjectNode().put("type", JSON_OBJECT_FORMAT);
    }
}
