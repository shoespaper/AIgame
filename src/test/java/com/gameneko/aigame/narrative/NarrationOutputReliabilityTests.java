package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.ProtocolVersion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证叙事模型的结构化输出约束、服务端校验与安全回退。 */
class NarrationOutputReliabilityTests {
    /** 验证 JSON Schema 使用严格模式并拒绝未声明字段。 */
    @Test
    void providerSchemaUsesStrictStructuredOutput() {
        var format = NarrativeProviderConfiguration.jsonSchemaResponseFormat(new ObjectMapper());

        assertEquals("json_schema", format.path("type").asText());
        assertTrue(format.path("json_schema").path("strict").asBoolean());
        assertFalse(format.path("json_schema").path("schema").path("additionalProperties").asBoolean());
    }

    /** 验证服务端会把越界模型响应整体替换为基于快照的本地叙事。 */
    @Test
    void invalidProviderResponseFallsBackAsAWhole() {
        NarrativeProvider provider = prompt -> new NarrativeResponse("x".repeat(6001), List.of(), List.of(),
                List.of(), List.of(), List.of(), ProtocolVersion.current());
        var service = new DefaultNarrationService(provider);

        var response = service.narrate(request());

        assertTrue(response.narration().contains("旅人"));
        assertFalse(response.narration().contains("x".repeat(100)));
    }

    /** 验证服务端保留合法响应且统一使用当前协议版本。 */
    @Test
    void validResponseIsPreservedWithServerProtocolVersion() {
        NarrativeProvider provider = prompt -> new NarrativeResponse("夜色降临。", List.of(), List.of(),
                List.of(), List.of(), List.of(), new ProtocolVersion(1, 7));
        var response = new DefaultNarrationService(provider).narrate(request());

        assertEquals("夜色降临。", response.narration());
        assertEquals(ProtocolVersion.current(), response.protocolVersion());
    }

    /** 验证类型化初始化值能够按结构化输出协议反序列化。 */
    @Test
    void initializationValuesDeserializeFromTaggedJson() throws Exception {
        var value = new ObjectMapper().readValue("{\"type\":\"integer\",\"value\":12}", InitializationValue.class);

        assertEquals(new InitializationValue.IntegerNumber(12), value);
    }

    /** 验证解析器拒绝额外字段及重复 JSON 键。 */
    @Test
    void strictJsonReaderRejectsUnknownAndDuplicateProperties() {
        var provider = new OpenAiCompatibleNarrativeProvider(org.springframework.web.client.RestClient.create(),
                new ObjectMapper(), "test-model");
        String valid = """
                {"narration":"可以","mediaRequests":[],"proposals":[],"options":[],"initializationQuestions":[],"parseCandidates":[]}
                """;

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> provider.readStrict(valid));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> provider.readStrict(valid.replace("\"narration\":\"可以\"", "\"narration\":\"可以\",\"surprise\":true")));
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> provider.readStrict(valid.replace("\"narration\":\"可以\"", "\"narration\":\"可以\",\"narration\":\"重复\"")));
    }

    /** 构造最小服务端可信叙事请求。 */
    private static NarrativeRequest request() {
        var snapshot = new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, "dawn", "spring", "clear",
                "村庄", "北境", "Low", List.of(), List.of(), List.of()), null, List.of());
        return new NarrativeRequest("test-game", 0, "观察四周", "zh-CN", snapshot);
    }
}
