package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.WorldbuildNarrationService;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 验证世界构建对话的 AI 声明驱动收集、枚举校验、列表合并、模板复用和失败重试。 */
class WorldbuildNarrationServiceTests {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 验证字段值由 AI 声明驱动，必填字段依次收集，非法枚举声明被服务端拒绝。 */
    @Test
    void requiredFieldsCollectedFromUpdatesAndEnumsValidated() {
        var service = new WorldbuildNarrationService(prompt -> {
            String input = prompt.userInput();
            if (input.contains("蒸汽")) return response(update("type", "蒸汽朋克"));
            if (input.contains("异世界")) return response(update("type", "isekai"));
            if (input.contains("改名")) return response(update("name", "灰烬王座"));
            if (input.contains("群星")) return response(update("name", "群星之城"));
            return response();
        }, mapper);
        var session = service.session("required");
        assertFalse(session.turn().done());

        var invalid = service.answer(session, "蒸汽朋克");
        assertFalse(invalid.collected().containsKey("type"));

        var type = service.answer(session, "异世界");
        assertEquals("isekai", type.collected().get("type"));
        assertFalse(type.done());

        var name = service.answer(session, "群星之城");
        assertTrue(name.done());
        var renamed = service.answer(session, "我想改名");
        assertTrue(renamed.done());
        assertEquals("灰烬王座", renamed.collected().get("name"));
    }

    /** 验证模型可以按服务端顺序提问并结构化合并多轮规则、势力与出身，非法声明不进入配置。 */
    @Test
    void schemaQuestionsCollectStructuredListsAndIgnoreInvalidUpdates() {
        var responses = new ArrayDeque<>(List.of(response(),
                response(update("type", "isekai")),
                response(update("world_tropes", "[\"双月\",\"魔法有代价\"]")),
                response(update("factions", "[{\"type\":\"联盟\",\"desc_hint\":\"守护双月\"}]")),
                response(update("name", "群星之城"), update("world_tropes", "[\"魔法有代价\",\"潮汐变幻\"]"), update("origin_contexts", "[\"流亡者\"]"))));
        var service = new WorldbuildNarrationService(prompt -> responses.removeFirst(), mapper);
        var session = service.session("lists");
        service.answer(session, "异世界");
        service.answer(session, "双月，魔法有代价");
        service.answer(session, "联盟守护双月");
        service.answer(session, "群星之城，补充潮汐变幻，流亡者开局");
        var done = session.turn();
        assertTrue(done.done());
        assertEquals(List.of("双月", "魔法有代价", "潮汐变幻"), done.collected().get("world_tropes"));
        assertEquals(List.of(Map.of("type", "联盟", "desc_hint", "守护双月")), done.collected().get("factions"));
        assertEquals(List.of("流亡者"), done.collected().get("origin_contexts"));
    }

    /** 验证外部模型失败后不会部分写入答案，且下一次请求仍可重试。 */
    @Test
    void providerFailureLeavesSessionUnchanged() {
        AtomicInteger calls = new AtomicInteger();
        NarrativeProvider provider = prompt -> {
            int call = calls.incrementAndGet();
            if (call == 2) throw new NarrativeProviderException("暂时失败", true);
            if (call == 1) return response();
            return response(update("type", "fantasy"));
        };
        var service = new WorldbuildNarrationService(provider, mapper);
        var session = service.session("retry");
        assertThrows(NarrativeProviderException.class, () -> service.answer(session, "奇幻"));
        assertTrue(session.turn().collected().isEmpty());
        assertEquals("fantasy", service.answer(session, "奇幻").collected().get("type"));
    }

    /** 验证非法列表声明不会进入配置，玩家明确跳过（空数组声明）后可以继续。 */
    @Test
    void malformedFactionsAreRejectedAndCanBeSkipped() {
        var responses = new ArrayDeque<>(List.of(response(),
                response(update("type", "isekai")),
                response(update("factions", "[{\"name\":\"错误结构\"}]")),
                response(update("factions", "[]"))));
        var service = new WorldbuildNarrationService(prompt -> responses.removeFirst(), mapper);
        var session = service.session("invalid-lists");
        service.answer(session, "异世界");
        var rejected = service.answer(session, "错误势力格式");
        assertFalse(rejected.collected().containsKey("factions"));
        assertEquals(List.of(), service.answer(session, "跳过").collected().get("factions"));
    }

    /** 验证未配置模板的新 ASCII 类型可以作为稳定标识被接受。 */
    @Test
    void acceptsNewAsciiWorldTypeWithoutTemplate() {
        WorldbuildNarrationService service = new WorldbuildNarrationService(prompt ->
                prompt.userInput().contains("steampunk")
                        ? response(update("type", "steampunk"))
                        : response(update("name", "铜雾城")), mapper);
        WorldbuildNarrationService.Session session = service.session("new-type");
        service.answer(session, "steampunk");
        WorldbuildNarrationService.Turn done = service.answer(session, "铜雾城");
        assertTrue(done.done());
        assertEquals("steampunk", done.collected().get("type"));
    }

    /** 验证世界类型推荐按钮来自 AI 的 options，而不是模板目录。 */
    @Test
    void forwardsAiGeneratedOptionsToQuestion() {
        WorldbuildNarrationService service = new WorldbuildNarrationService(prompt ->
                responseWithOptions(List.of("蒸汽朋克", "海洋殖民", "过长的推荐选项应该被过滤掉，因为超过二十字")), mapper);
        WorldbuildNarrationService.Session session = service.session("ai-options");
        assertEquals(List.of("蒸汽朋克", "海洋殖民"), session.turn().options());
    }

    /** 验证跳过必填字段后不会立刻重复提问，同时仍保留缺失状态等待后续补填。 */
    @Test
    void skippedRequiredFieldIsNotAskedAgainImmediately() {
        WorldbuildNarrationService service = new WorldbuildNarrationService(prompt ->
                prompt.userInput().contains("异世界") ? response(update("type", "isekai")) : response(), mapper);
        WorldbuildNarrationService.Session session = service.session("skip-required");
        service.answer(session, "异世界");
        service.answer(session, "跳过");
        service.answer(session, "跳过");
        WorldbuildNarrationService.Turn afterNameSkip = service.answer(session, "跳过");
        assertTrue(afterNameSkip.done());
        assertFalse(afterNameSkip.options().contains("这个世界叫什么名字？"));
        assertEquals("未命名世界", afterNameSkip.collected().get("name"));
    }

    /** 创建一个类型化确定字段声明。
     * @param field 世界构建字段标识
     * @param value 文本或 JSON 数组文本
     * @return 字段和值的映射项
     */
    private static Map.Entry<String, InitializationValue> update(String field, String value) {
        return Map.entry(field, new InitializationValue.Text(value));
    }

    /** 创建只声明确定字段值的模型响应。
     * @param updates 本轮确定字段值
     * @return 不包含问题结构的叙事响应
     */
    @SafeVarargs
    private static NarrativeResponse response(Map.Entry<String, InitializationValue>... updates) {
        return new NarrativeResponse("已记录。", null, List.of(), Map.ofEntries(updates));
    }

    /** 创建带有 AI 推荐选项的模型响应。
     * @param options 模型生成的推荐选项
     * @return 带推荐选项且不声明字段的叙事响应
     */
    private static NarrativeResponse responseWithOptions(List<String> options) {
        return new NarrativeResponse("请选择一种方向。", null, options, Map.of());
    }
}
