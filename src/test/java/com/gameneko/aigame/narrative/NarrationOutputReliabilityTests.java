package com.gameneko.aigame.narrative;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.InitializationValue;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

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

        assertThrows(NarrativeProviderException.class, () -> service.narrate(request(), List.of()));
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
        assertEquals(65536, new com.gameneko.aigame.application.NarrativeRuntimeSettingsService.Settings(
                "k", true, "", "", 99999).maxTokens());
    }

    /** 验证输出截断时扩大 token 预算，且每轮分别记录发出的消息和收到的正文。
     * @throws Exception 测试请求或响应验证失败
     */
    @Test
    void truncatedOutputRetriesWithLargerTokenBudget() throws Exception {
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://model.test");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        String truncated = """
                {"choices":[{"message":{"content":"{\\"narration\\":\\"半句"},"finish_reason":"length"}],"usage":{"completion_tokens":256}}
                """;
        String completed = """
                {"choices":[{"message":{"content":"{ \\"narration\\":\\"夜色降临。\\",\\"action\\":null,\\"options\\":[\\"继续前进\\"],\\"updates\\":{} }"},"finish_reason":"stop"}],"usage":{"completion_tokens":120}}
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
        Logger logger = (Logger) LoggerFactory.getLogger("com.gameneko.aigame.narrative.diagnostics");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        NarrativeResponse response;
        try {
            response = provider.respond(new NarrativePrompt("system", "input", snapshot(), "zh-CN"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals("夜色降临。", response.narration());
        List<ILoggingEvent> attempts = appender.list.stream()
                .filter(event -> List.of("narrative.retry", "narrative.completed").contains(event.getMessage()))
                .toList();
        assertEquals(3, attempts.size());
        assertEquals("{\"narration\":\"半句", logField(attempts.get(0), "modelResponse"));
        assertEquals("{\"narration\":\"半句", logField(attempts.get(1), "modelResponse"));
        assertEquals("{ \"narration\":\"夜色降临。\",\"action\":null,\"options\":[\"继续前进\"],\"updates\":{} }",
                logField(attempts.get(2), "modelResponse"));
        for (ILoggingEvent attempt : attempts) {
            assertEquals("system\n\n" + NarrativeProviderConfiguration.SCHEMA_DESCRIPTION, logField(attempt, "systemMessage"));
            assertEquals("input", new ObjectMapper().readTree((String) logField(attempt, "userMessage"))
                    .path("playerInput").asText());
        }
        server.verify();
    }

    /** 验证 JSON 模式发送的消息包含关键词，且上游诊断依据实际请求。 */
    @Test
    void upstreamErrorsLogOnlyClassifiedDiagnostics() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://model.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String url = "https://model.test/chat/completions";
        server.expect(requestTo(url))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[0].content").value("判定\n\n只返回一个 JSON 对象。"))
                .andExpect(jsonPath("$.messages[1].content").value("草案"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"error":{"message":"json_object requires messages: must contain the word 'json'. private-upstream-text",
                                  "code":"invalid_request_error","param":"response_format"}}
                        """));
        server.expect(requestTo(url))
                .andExpect(jsonPath("$.messages[0].content").value("识别\n\n只返回一个 JSON 对象。"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("private-proxy-text"));
        server.expect(requestTo(url))
                .andExpect(jsonPath("$.messages[0].content").value("输出 JSON"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"error":{"message":"private-unknown-text","code":"private-code","param":"private-param"}}
                        """));

        OpenAiCompatibleNarrativeProvider provider = new OpenAiCompatibleNarrativeProvider(builder.build(),
                new ObjectMapper(), "test-model");
        Logger logger = (Logger) LoggerFactory.getLogger("com.gameneko.aigame.narrative.diagnostics");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            assertThrows(NarrativeProviderException.class,
                    () -> provider.completeJson("world_draft_judge", "判定", "草案"));
            assertThrows(NarrativeProviderException.class,
                    () -> provider.completeJson("world_draft_identify", "识别", "草案"));
            assertThrows(NarrativeProviderException.class,
                    () -> provider.completeJson("world_draft_generation", "输出 JSON", "世界描述"));
        } finally {
            RequestContextHolder.setRequestAttributes(previous);
            logger.detachAppender(appender);
            appender.stop();
        }

        List<ILoggingEvent> attempts = appender.list.stream()
                .filter(event -> "narrative.completed".equals(event.getMessage())).toList();
        assertEquals(3, attempts.size());
        ILoggingEvent judge = attempts.getFirst();
        assertEquals("world_draft_judge", logField(judge, "operation"));
        assertEquals(1, logField(judge, "callIndex"));
        assertEquals(1, logField(judge, "attempt"));
        assertEquals("json_object", logField(judge, "responseFormat"));
        assertEquals(400, logField(judge, "upstreamStatus"));
        assertEquals("json_mode_prompt_missing_keyword", logField(judge, "upstreamDiagnosis"));
        assertEquals("upstream_error", logField(judge, "diagnosisSource"));
        assertEquals("response_format", logField(judge, "upstreamErrorParam"));
        assertEquals("判定\n\n只返回一个 JSON 对象。", logField(judge, "systemMessage"));
        assertEquals(2, logField(attempts.get(1), "callIndex"));
        assertEquals("unknown", logField(attempts.get(1), "diagnosisSource"));
        assertEquals(3, logField(attempts.get(2), "callIndex"));
        assertEquals("unclassified_upstream_error", logField(attempts.get(2), "upstreamDiagnosis"));
        for (ILoggingEvent attempt : attempts) {
            assertFalse(attempt.getKeyValuePairs().toString().contains("private-"));
            assertEquals(null, logField(attempt, "modelResponse"));
        }
        server.verify();
    }

    /** 验证缺少关键词的原始请求仍可按本地证据归类。 */
    @Test
    void diagnosesMissingKeywordWhenOnlyRequestProvidesEvidence() {
        HttpClientErrorException error = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);

        assertEquals("request_inspection", ModelHttpErrorDiagnostics.classify(new ObjectMapper(), error,
                "json_object", "判定草案", "世界描述").source());
    }

    /** 获取结构化日志中的指定字段。
     * @param event 单轮模型调用日志
     * @param key 待检查的字段名
     */
    private static Object logField(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> key.equals(pair.key)).findFirst().orElseThrow().value;
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
                "村庄", "北境", List.of(), List.of(), List.of()), List.of());
    }

    /** 构造最小服务端可信叙事请求。 */
    private static NarrativeRequest request() {
        return new NarrativeRequest("test-game", 0, "观察四周", "zh-CN", snapshot());
    }
}
