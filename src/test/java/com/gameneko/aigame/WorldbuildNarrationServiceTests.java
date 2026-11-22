package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.WorldbuildNarrationService;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 验证世界构建对话的 AI 候选驱动收集、枚举校验、列表合并、模板复用和失败重试。 */
class WorldbuildNarrationServiceTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(mapper, new DefaultResourceLoader());

    /** 验证字段值由 AI 候选驱动，必填字段依次收集，非法枚举候选被服务端拒绝。 */
    @Test
    void requiredFieldsCollectedFromCandidatesAndEnumsValidated() {
        var service = new WorldbuildNarrationService(prompt -> {
            String input = prompt.userInput();
            if (input.contains("蒸汽")) return response("type", candidate("type", "蒸汽朋克"));
            if (input.contains("异世界")) return response("name", candidate("type", "异世界"));
            if (input.contains("群星")) return response("tone", candidate("name", "群星之城"));
            if (input.contains("平衡")) return response(null, candidate("tone", "平衡"));
            return response("type");
        }, mapper, catalog);
        var session = service.session("required", null);
        assertEquals("type", session.turn().question().fieldId());
        assertFalse(session.turn().done());

        var invalid = service.answer(session, "蒸汽朋克");
        assertEquals("type", invalid.question().fieldId());
        assertFalse(invalid.collected().containsKey("type"));

        var type = service.answer(session, "异世界");
        assertEquals("isekai", type.collected().get("type"));
        assertEquals("isekai", type.templateId());
        assertEquals("name", type.question().fieldId());

        var name = service.answer(session, "群星之城");
        assertEquals("tone", name.question().fieldId());

        var done = service.answer(session, "平衡");
        assertTrue(done.done());
        assertEquals("balanced", done.collected().get("tone"));
        assertNull(done.question());
    }

    /** 验证模型可以动态提问并结构化合并多轮规则、势力与出身，非法候选不进入配置。 */
    @Test
    void dynamicQuestionsCollectStructuredListsAndIgnoreInvalidCandidates() {
        var responses = new ArrayDeque<>(List.of(response("type"),
                response("world_tropes", candidate("type", "异世界")),
                response("factions", candidate("world_tropes", "[\"双月\",\"魔法有代价\"]"), candidate("tone", "invalid")),
                response("name", candidate("factions", "[{\"type\":\"联盟\",\"desc_hint\":\"守护双月\"}]")),
                response("tone", candidate("name", "群星之城"), candidate("world_tropes", "[\"魔法有代价\",\"潮汐变幻\"]"), candidate("origin_contexts", "[\"流亡者\"]")),
                response(null, candidate("tone", "硬核"))));
        var service = new WorldbuildNarrationService(prompt -> responses.removeFirst(), mapper, catalog);
        var session = service.session("lists", null);
        service.answer(session, "异世界");
        service.answer(session, "双月，魔法有代价");
        assertFalse(session.turn().collected().containsKey("tone"));
        service.answer(session, "联盟守护双月");
        service.answer(session, "群星之城，补充潮汐变幻，流亡者开局");
        var done = service.answer(session, "硬核");
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
            if (call == 1) return response("type");
            return response("name", candidate("type", "奇幻"));
        };
        var service = new WorldbuildNarrationService(provider, mapper, catalog);
        var session = service.session("retry", null);
        assertThrows(NarrativeProviderException.class, () -> service.answer(session, "奇幻"));
        assertTrue(session.turn().collected().isEmpty());
        assertEquals("fantasy", service.answer(session, "奇幻").collected().get("type"));
        assertEquals("", session.turn().templateId());
    }

    /** 验证非法列表候选不会进入配置，玩家明确跳过（空数组候选）后可以继续。 */
    @Test
    void malformedFactionsAreRejectedAndCanBeSkipped() {
        var responses = new ArrayDeque<>(List.of(response("type"),
                response("factions", candidate("type", "赛博朋克")),
                response("name", candidate("factions", "[{\"name\":\"错误结构\"}]")),
                response("name", candidate("factions", "[]"))));
        var service = new WorldbuildNarrationService(prompt -> responses.removeFirst(), mapper, catalog);
        var session = service.session("invalid-lists", null);
        service.answer(session, "赛博朋克");
        var rejected = service.answer(session, "错误势力格式");
        assertFalse(rejected.collected().containsKey("factions"));
        assertEquals("factions", rejected.question().fieldId());
        assertEquals(List.of(), service.answer(session, "跳过").collected().get("factions"));
    }

    /** 构造模型结构化候选。
     * @param field 世界构建字段标识
     * @param value 文本或 JSON 数组文本
     * @return 类型化候选
     */
    private static NarrativeParseCandidate candidate(String field, String value) {
        return new NarrativeParseCandidate(field, new InitializationValue.Text(value), 1);
    }

    /** 构造可选问题和候选组成的模型响应。
     * @param field 问题字段，空值表示模型结束
     * @param candidates 模型解析的候选
     * @return 叙事模型响应
     */
    private static NarrativeResponse response(String field, NarrativeParseCandidate... candidates) {
        return new NarrativeResponse("已记录。", null, List.of(),
                field == null ? List.of() : List.of(new NarrativeInitializationQuestion(field, "请确认" + field, List.of())),
                List.of(candidates));
    }
}
