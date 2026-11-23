package com.gameneko.aigame.narrative;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.InitializationValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证叙事模型的结构化输出约束、服务端校验与解析健壮性。 */
class NarrationOutputReliabilityTests {
    /** 验证响应格式为 json_object 模式，并附带字段结构描述。 */
    @Test
    void providerUsesJsonObjectResponseFormat() {
        var format = NarrativeProviderConfiguration.jsonObjectResponseFormat(new ObjectMapper());

        assertEquals("json_object", format.path("type").asText());
        assertFalse(NarrativeProviderConfiguration.SCHEMA_DESCRIPTION.isBlank());
        assertTrue(NarrativeProviderConfiguration.SCHEMA_DESCRIPTION.contains("narration"));
    }

    /** 验证服务端对越界模型响应抛出异常，而不是静默降级。 */
    @Test
    void invalidProviderResponseIsRejected() {
        NarrativeProvider provider = prompt -> new NarrativeResponse("x".repeat(6001));
        var service = new DefaultNarrationService(provider);

        assertThrows(IllegalArgumentException.class, () -> service.narrate(request(), List.of()));
    }

    /** 验证服务端保留合法纯叙事响应。 */
    @Test
    void validResponseIsPreserved() {
        NarrativeProvider provider = prompt -> new NarrativeResponse("夜色降临。", null, List.of("继续前进"));
        var response = new DefaultNarrationService(provider).narrate(request(), List.of());

        assertEquals("夜色降临。", response.narration());
        assertEquals(null, response.action());
        assertEquals(List.of("继续前进"), response.options());
    }

    /** 验证类型化初始化值能够按结构化输出协议反序列化。 */
    @Test
    void initializationValuesDeserializeFromTaggedJson() throws Exception {
        var value = new ObjectMapper().readValue("{\"type\":\"integer\",\"value\":12}", InitializationValue.class);

        assertEquals(new InitializationValue.IntegerNumber(12), value);
    }

    /** 验证解析器容忍未知字段与代码围栏，但拒绝重复 JSON 键。 */
    @Test
    void strictJsonReaderToleratesUnknownFieldsAndFencesButRejectsDuplicates() {
        var provider = new OpenAiCompatibleNarrativeProvider(org.springframework.web.client.RestClient.create(),
                new ObjectMapper(), "test-model");
        String valid = """
                {"narration":"可以","action":null,"options":["继续前进"],"updates":{}}
                """;

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> provider.readStrict(
                valid.replace("\"narration\":\"可以\"", "\"narration\":\"可以\",\"surprise\":true")));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> provider.readStrict("```json\n" + valid + "\n```"));
        assertThrows(Exception.class, () -> provider.readStrict(
                valid.replace("\"narration\":\"可以\"", "\"narration\":\"可以\",\"narration\":\"重复\"")));
    }

    /** 验证更新对象支持全部四种类型，并拒绝未声明的类型标签。 */
    @Test
    void updatesDeserializeWithFourFiniteValueTypes() throws Exception {
        var provider = new OpenAiCompatibleNarrativeProvider(org.springframework.web.client.RestClient.create(),
                new ObjectMapper(), "test-model");
        String json = """
                {"narration":"已确认","action":null,"options":[],"updates":{
                  "name":{"type":"text","value":"凯尔"},
                  "age":{"type":"integer","value":16},
                  "ratio":{"type":"decimal","value":1.5},
                  "enabled":{"type":"flag","value":true}
                }}
                """;
        var wire = provider.readStrict(json);
        assertEquals(new InitializationValue.Text("凯尔"), wire.updates().get("name"));
        assertEquals(new InitializationValue.IntegerNumber(16), wire.updates().get("age"));
        assertEquals(new InitializationValue.DecimalNumber(1.5), wire.updates().get("ratio"));
        assertEquals(new InitializationValue.Flag(true), wire.updates().get("enabled"));
        assertThrows(Exception.class, () -> provider.readStrict(json.replace("\"flag\"", "\"array\"")));
    }

    /** 验证输出上限缺失或越界时收敛到允许区间，避免旧配置文件读出 0。 */
    @Test
    void maxTokensSettingIsNormalized() {
        assertEquals(2048, new com.gameneko.aigame.application.NarrativeRuntimeSettingsService.Settings(
                "k", true, "", "", null).maxTokens());
        assertEquals(2048, new com.gameneko.aigame.application.NarrativeRuntimeSettingsService.Settings(
                "k", true, "", "", 10).maxTokens());
        assertEquals(4096, new com.gameneko.aigame.application.NarrativeRuntimeSettingsService.Settings(
                "k", true, "", "", 4096).maxTokens());
        assertEquals(16384, new com.gameneko.aigame.application.NarrativeRuntimeSettingsService.Settings(
                "k", true, "", "", 99999).maxTokens());
    }

    /** 验证 finish_reason=length 时按倍数提高 max_tokens 重试，而不是直接失败。 */
    @Test
    void truncatedOutputRetriesWithLargerTokenBudget() throws Exception {
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://model.test");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        String truncated = """
                {"choices":[{"message":{"content":"{\\"narration\\":\\"半句"},"finish_reason":"length"}],"usage":{"completion_tokens":256}}
                """;
        String completed = """
                {"choices":[{"message":{"content":"{\\"narration\\":\\"夜色降临。\\",\\"action\\":null,\\"options\\":[\\"继续前进\\"],\\"updates\\":{}}"},"finish_reason":"stop"}],"usage":{"completion_tokens":120}}
                """;
        for (int expected : new int[]{256, 512}) {
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://model.test/chat/completions"))
                    .andExpect(request -> assertEquals(expected, maxTokensOf(request)))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            truncated, org.springframework.http.MediaType.APPLICATION_JSON));
        }
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://model.test/chat/completions"))
                .andExpect(request -> assertEquals(1024, maxTokensOf(request)))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        completed, org.springframework.http.MediaType.APPLICATION_JSON));

        var provider = new OpenAiCompatibleNarrativeProvider(builder.build(), new ObjectMapper(), "test-model", 256);
        var response = provider.respond(new NarrativePrompt("system", "input", snapshot(), "zh-CN"));

        assertEquals("夜色降临。", response.narration());
        server.verify();
    }

    /** 读取模拟请求体中的 max_tokens 字段。 */
    private static int maxTokensOf(org.springframework.http.client.ClientHttpRequest request) {
        String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
        try {
            return new ObjectMapper().readTree(body).path("max_tokens").asInt();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 构造最小可信快照。 */
    private static NarrativeSnapshot snapshot() {
        return new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, "dawn",
                "村庄", "北境", "Low", List.of(), List.of(), List.of()), null, List.of());
    }

    /** 构造最小服务端可信叙事请求。 */
    private static NarrativeRequest request() {
        return new NarrativeRequest("test-game", 0, "观察四周", "zh-CN", snapshot());
    }
}
