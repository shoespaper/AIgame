package com.gameneko.aigame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.CreationProgress;
import com.gameneko.aigame.application.GameRequestException;
import com.gameneko.aigame.application.WorldbuildNarrationService;
import com.gameneko.aigame.application.port.WorldbookRepository;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.narrative.WorldDraftModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证世界草案验收、版本和保存协议。 */
class WorldbuildNarrationServiceTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorldbookRepository repository = mock(WorldbookRepository.class);
    private final WorldBookCatalog worlds = mock(WorldBookCatalog.class);
    private final TemplateCatalog templates = mock(TemplateCatalog.class);

    /** 验证正文长度边界、自动修订和有效 Judge 信号。 */
    @Test
    void revisesInvalidLengthAndJudgeIssuesWithinOneAnswer() {
        String boundary = "荒".repeat(1997) + "。\n🌙";
        Deque<ModelCall> calls = new ArrayDeque<>();
        calls.add(new ModelCall("世界构建 GM", null, generation(boundary + "！")));
        calls.add(new ModelCall("世界构建 GM", "2000", generation("背景与当前局势")));
        calls.add(new ModelCall("世界草案验收员", "背景与当前局势",
                mapper.valueToTree(Map.of("status", "REVISE", "issues", List.of("缺少开局切入点")))));
        calls.add(new ModelCall("世界构建 GM", "缺少开局切入点", generation(boundary)));
        calls.add(new ModelCall("世界草案验收员", null, pass()));
        WorldbuildNarrationService service = service(calls);
        WorldbuildNarrationService.Session session = service.session("boundary");
        assertFalse(session.turn().done());

        WorldbuildNarrationService.Turn turn = service.answer(session, "帮我构建异世界");
        assertTrue(turn.done());
        assertEquals(boundary, turn.draft());
        assertEquals(1L, turn.version());
        assertTrue(calls.isEmpty());
    }

    /** 验证纯对话沿用已验收版本，非法 Judge 信号无法覆盖旧稿。 */
    @Test
    void retainsAcceptedDraftOnQuestionsAndFailedReview() {
        Deque<ModelCall> calls = new ArrayDeque<>();
        calls.add(new ModelCall("世界构建 GM", null, generation("已验收草案")));
        calls.add(new ModelCall("世界草案验收员", null, pass()));
        calls.add(new ModelCall("世界构建 GM", null, mapper.valueToTree(Map.of("reply", "公会各自筹粮。"))));
        calls.add(new ModelCall("世界构建 GM", null, generation("尚未验收的修改")));
        for (int round = 1; round <= 5; round++) {
            calls.add(new ModelCall(round == 1 ? "世界草案验收员" : "上次回复不符合输出协议", "尚未验收的修改",
                    mapper.valueToTree(Map.of("status", "ALLOW"))));
        }
        WorldbuildNarrationService service = service(calls);
        WorldbuildNarrationService.Session session = service.session("review");
        WorldbuildNarrationService.Turn accepted = service.answer(session, "松散的公会");
        WorldbuildNarrationService.Turn discussion = service.answer(session, "公会为什么难以合作？");
        assertEquals(accepted.version(), discussion.version());
        assertEquals(accepted.draft(), discussion.draft());
        List<CreationProgress> progress = new ArrayList<>();
        GameRequestException failure = assertThrows(GameRequestException.class,
                () -> service.answer(session, "换个开局", progress::add));
        assertEquals(GameRequestException.Code.WORLD_DRAFT_ATTEMPTS_EXHAUSTED, failure.code());
        assertEquals(List.of(1, 2, 3, 4, 5), progress.stream().filter(CreationProgress::failed)
                .map(CreationProgress::attempt).toList());
        assertSame(discussion, session.turn());
        assertTrue(calls.isEmpty());
    }

    /** 验证第五轮验收通过，Judge 临时失败只重试验收同一候选。 */
    @Test
    void passesOnFifthRoundAfterRetryingSameCandidate() {
        Deque<ModelCall> calls = new ArrayDeque<>();
        calls.add(new ModelCall("世界构建 GM", null, mapper.createObjectNode()));
        calls.add(new ModelCall("世界构建 GM", "2000", generation("待验收草案")));
        calls.add(new ModelCall("世界草案验收员", "待验收草案", mapper.createObjectNode()));
        calls.add(new ModelCall("上次回复不符合输出协议", "待验收草案",
                mapper.valueToTree(Map.of("status", "REVISE", "issues", List.of("补充码头开局")))));
        calls.add(new ModelCall("世界构建 GM", "补充码头开局", generation("码头开局草案")));
        calls.add(new ModelCall("世界草案验收员", "码头开局草案", mapper.createObjectNode()));
        calls.add(new ModelCall("上次回复不符合输出协议", "码头开局草案", pass()));
        WorldbuildNarrationService service = service(calls);
        List<CreationProgress> progress = new ArrayList<>();
        WorldbuildNarrationService.Turn turn = service.answer(service.session("fifth"), "港口冒险", progress::add);
        assertEquals("码头开局草案", turn.draft());
        assertEquals(5, progress.getLast().attempt());
        assertTrue(calls.isEmpty());
    }

    /** 验证保存时提取身份、失败后可重试、成功后可重放。 */
    @Test
    void extractsTypeOnlyWhenSavingTheAcceptedVersion() {
        when(worlds.available()).thenReturn(List.of());
        when(templates.availableTemplates()).thenReturn(List.of(WorldTemplate.empty("isekai", "异世界")));
        Deque<ModelCall> calls = new ArrayDeque<>();
        calls.add(new ModelCall("世界构建 GM", null, generation("# 双月城\n\n双月初升，港口封锁。")));
        calls.add(new ModelCall("世界草案验收员", null, pass()));
        calls.add(new ModelCall("稳定类型 ID", "isekai", mapper.valueToTree(Map.of("name", "双月城", "type", "异世界"))));
        calls.add(new ModelCall("稳定类型 ID", "isekai", mapper.valueToTree(Map.of("name", "双月城", "type", "isekai"))));
        WorldbuildNarrationService service = service(calls);
        WorldbuildNarrationService.Session session = service.session("saving");
        WorldbuildNarrationService.Turn turn = service.answer(session, "双月照耀的异世界");
        assertThrows(GameRequestException.class, () -> service.save(session, 0));
        assertThrows(NarrativeProviderException.class, () -> service.save(session, turn.version()));
        assertSame(turn, session.turn());

        WorldbookDefinition saved = service.save(session, turn.version());
        assertEquals("isekai", saved.type());
        assertEquals(turn.draft(), saved.descriptionHint());
        assertSame(saved, service.save(session, turn.version()));
        verify(repository).save(saved);
        assertTrue(calls.isEmpty());
    }

    /** 按预期顺序模拟模型请求。
     * @param calls 预期的提示词、用户消息片段和模型结果
     */
    private WorldbuildNarrationService service(Deque<ModelCall> calls) {
        return new WorldbuildNarrationService(new WorldDraftModel((operation, instruction, input) -> {
            ModelCall call = calls.removeFirst();
            assertTrue(instruction.contains(call.instruction()));
            if (call.input() != null) assertTrue(input.contains(call.input()), input);
            return call.result();
        }, mapper), repository, worlds, templates);
    }

    /** 构造生成结果。
     * @param draft 完整 Markdown 正文
     */
    private JsonNode generation(String draft) {
        return mapper.valueToTree(Map.of("draft", draft, "reply", "草案已更新。"));
    }

    /** 构造 Judge 通过信号。 */
    private JsonNode pass() {
        return mapper.valueToTree(Map.of("status", "PASS"));
    }

    /** 记录一次预期模型调用和返回内容。 */
    private record ModelCall(String instruction, String input, JsonNode result) {}
}
