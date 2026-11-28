package com.gameneko.aigame;

import com.gameneko.aigame.narrative.NarrativeProviderException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.api.InitializationSessionController;
import com.gameneko.aigame.api.InitializationSessionController.ConfirmRequest;
import com.gameneko.aigame.application.*;
import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.engine.*;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import com.gameneko.aigame.infrastructure.ClasspathGameStateTypeResolver;
import com.gameneko.aigame.narrative.*;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;
import com.gameneko.aigame.persistence.FileGameRepository;
import com.gameneko.aigame.rulesets.ap.ApTemplatePolicy;
import com.gameneko.aigame.application.port.GamePersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证确定字段声明在世界状态、回合版本及真实存档之间的一致性。 */
class NarrativeUpdatesTests {
    @TempDir Path saves;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(mapper, new DefaultResourceLoader());
    private final ClasspathGameEngineFactory engines = new ClasspathGameEngineFactory();

    /** 验证纯叙事也能保存地点、区域及标量覆盖，并按访问顺序去重记录地点。 */
    @Test
    void worldUpdatesPersistWithoutEngineAndAccumulateLocations() {
        var repository = repository();
        var initial = GameInstance.narrativeOnly("travel", new WorldInitializationService(catalog).initialize(request("石桥镇")));
        repository.save(initial);
        assertEquals(List.of("石桥镇"), initial.worldState().visitedLocations());
        var responses = new ArrayDeque<>(List.of(
                response(Map.of("location", text("古堡"), "region", text("雾谷废墟"), "name", text("新世界"))),
                response(Map.of("location", text("石桥镇"), "region", new InitializationValue.Flag(false), "time", text("黄昏"))),
                response(Map.of("visitedLocations", text("古堡"), "mainQuest", text("寻找钥匙")))));
        var service = service(repository, responses);
        var first = service.narrate("travel", 0, "去古堡", "zh-CN");
        assertEquals(1, first.instance().revision());
        assertEquals("雾谷废墟", first.instance().worldState().currentRegion());
        assertEquals("新世界", first.instance().worldState().setting());
        service.narrate("travel", 1, "回镇", "zh-CN");
        var last = service.narrate("travel", 2, "观察", "zh-CN");
        var loaded = repository.load("travel").orElseThrow();
        assertEquals(last.instance(), loaded);
        assertEquals(3, loaded.worldState().revision());
        assertEquals("石桥镇", loaded.worldState().location());
        assertEquals("雾谷废墟", loaded.worldState().currentRegion());
        assertEquals("黄昏", loaded.worldState().time());
        assertEquals("寻找钥匙", loaded.worldState().mainQuest());
        assertEquals(List.of("石桥镇", "古堡"), loaded.worldState().visitedLocations());
    }

    /** 验证区域可在开场写入，只有错误类型或未知字段时跳过保存和版本推进。 */
    @Test
    void invalidFieldsAreIgnoredIndependentlyAndOpeningCanSetLocation() {
        var repository = repository();
        WorldState world = new WorldInitializationService(catalog).initialize("isekai_grey");
        GameInstance<?> initial = GameInstance.narrativeOnly("opening", world);
        var responses = new ArrayDeque<>(List.of(
                response(Map.of("location", text("石桥镇"), "region", text("town"))),
                response(Map.of("region", new InitializationValue.IntegerNumber(1), "weather", text("雨"), "season", text("冬"),
                        "unknown", text("无效")))));
        var service = service(repository, responses);
        NarrativeApplicationService.NarratedTurn opening = service.prepareOpening(initial, "zh-CN");
        assertEquals(1, opening.instance().revision());
        assertEquals(List.of("石桥镇"), opening.instance().worldState().visitedLocations());
        assertTrue(repository.load("opening").isEmpty());
        repository.save(opening.instance());
        var turn = service.narrate("opening", 1, "观察", "zh-CN");
        assertEquals(opening.instance(), turn.instance());
        assertEquals("town", turn.instance().worldState().currentRegion());
        assertEquals(opening.instance().worldState(), turn.instance().worldState());
    }

    /** 验证开场和首次写档分别失败后，同一会话可以恢复，且并发确认只提交一次。
     * @throws Exception 并发确认没有在测试时限内完成
     */
    @Test
    void initializationRetriesOpeningThenSaveWithoutCreatingDuplicateGames() throws Exception {
        CountingRepository repository = new CountingRepository(null);
        AtomicInteger openingCalls = new AtomicInteger();
        NarrativeApplicationService narratives = new NarrativeApplicationService(repository, catalog,
                new DefaultNarrationService(prompt -> {
                    assertEquals(new CharacterProfile("凯尔", 19, "未指定", "商队向导"), prompt.snapshot().character());
                    if (openingCalls.incrementAndGet() == 1) throw new NarrativeProviderException("模型暂时不可用", true);
                    return response(Map.of("location", text("石桥镇"), "region", text("town")));
                }), new GameActionService(repository, catalog, engines));
        GameInitializationFlow flow = new GameInitializationFlow(new GameInitializationSchemaService(catalog, engines),
                new GameInstanceService(new WorldInitializationService(catalog), catalog, engines),
                narratives, new GameSaveService(repository));
        GameInitializationFlow.StartedSession started = flow.start(new GameSelection("isekai_grey", null));
        GameInitializationFlow.CheckedForm checked = flow.validate(started.id(),
                Map.of("characterName", "凯尔", "age", 19, "gender", "未指定", "background", "商队向导"), Map.of());
        assertNotNull(checked.version());
        assertEquals(0, openingCalls.get());

        assertThrows(NarrativeProviderException.class, () -> flow.confirm(started.id(), checked.version()));
        assertEquals(0, repository.writes);
        assertNull(repository.lastAttempt);

        repository.failNextSave = true;
        assertThrows(GamePersistenceException.class, () -> flow.confirm(started.id(), checked.version()));
        assertEquals(0, repository.writes);
        GameInstance<?> prepared = repository.lastAttempt;
        assertEquals(1, prepared.revision());

        Callable<NarrativeApplicationService.NarratedTurn> confirm = () -> flow.confirm(started.id(), checked.version());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<NarrativeApplicationService.NarratedTurn>> responses =
                    executor.invokeAll(List.of(confirm, confirm), 5, TimeUnit.SECONDS);
            NarrativeApplicationService.NarratedTurn first = responses.getFirst().get();
            assertSame(first, responses.getLast().get());
            assertSame(first, flow.confirm(started.id(), checked.version()));
            assertSame(prepared, first.instance());
            assertEquals("石桥镇", repository.load(prepared.gameId()).orElseThrow().worldState().location());
            assertEquals(2, openingCalls.get());
            assertEquals(1, repository.writes);
        }
    }

    /** 验证五次失败阻止开场与写档，重试可复用原表单并继续确认。
     * @throws Exception 测试端点或临时模板目录发生错误
     */
    @Test
    void finalizationBlocksAfterFiveRejectedProposalsAndRetriesTheSameForm() throws Exception {
        CountingRepository repository = new CountingRepository(null);
        AtomicInteger proposals = new AtomicInteger();
        AtomicInteger openingCalls = new AtomicInteger();
        TemplateCatalog emptyTemplates = mock(TemplateCatalog.class);
        when(emptyTemplates.findTemplate("isekai")).thenReturn(Optional.empty());
        Path registryFile = saves.resolve("registry.json");
        ConfirmedTemplateRegistry registry = new ConfirmedTemplateRegistry(mapper, prompt -> {
            if (proposals.incrementAndGet() <= 5) return response(Map.of("template", text("{}")));
            return response(Map.of("template", text("""
                    {"world_template":{"template_id":"isekai","world_type":"isekai",
                      "regions":[],"npc_types":[],"monster_types":[],"skill_types":[],"spell_types":[],
                      "talent_types":[],"item_types":[],"quest_templates":[],"event_templates":[]}}
                    """)));
        }, emptyTemplates, List.of(new ApTemplatePolicy(mapper)), registryFile.toString());
        ReflectionTestUtils.invokeMethod(engines, "configureTemplates", registry);

        GameInstanceService instances = new GameInstanceService(new WorldInitializationService(catalog), catalog, engines);
        ReflectionTestUtils.invokeMethod(instances, "configureTemplates", registry);
        NarrativeApplicationService narratives = new NarrativeApplicationService(repository, catalog,
                new DefaultNarrationService(prompt -> {
                    if (openingCalls.incrementAndGet() == 1) throw new NarrativeProviderException("开场暂不可用", true);
                    return new NarrativeResponse("冒险开始。", null, List.of("四处看看"));
                }),
                new GameActionService(repository, catalog, engines));
        GameInitializationFlow flow = new GameInitializationFlow(new GameInitializationSchemaService(catalog, engines), instances, narratives, new GameSaveService(repository));
        InitializationSessionController controller = new InitializationSessionController(flow);
        GameInitializationFlow.StartedSession started = flow.start(new GameSelection("cinder_continent", "ap"));
        GameInitializationFlow.CheckedForm checked = flow.validate(started.id(),
                Map.of("characterName", "凯尔", "age", 19, "gender", "男", "background", "商队向导"),
                Map.of("archetype", "swordsman", "difficulty", "adventure"));
        assertNotNull(checked.version());
        assertEquals(0, proposals.get());

        ConfirmRequest confirmation = new ConfirmRequest(checked.version());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MvcResult streamed = mvc.perform(post("/api/initialization/sessions/{id}/finalize/stream", started.id())
                        .accept("text/event-stream").contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(confirmation)))
                .andExpect(MockMvcResultMatchers.request().asyncStarted()).andReturn();
        streamed.getAsyncResult(8000);
        String frames = mvc.perform(asyncDispatch(streamed)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> events = frames.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5)).toList();
        assertEquals("TEMPLATE_ATTEMPTS_EXHAUSTED", mapper.readTree(events.getLast()).path("code").asText());
        assertEquals(5, events.stream().filter(line -> line.contains("\"failed\":true")).count());
        assertEquals(5, proposals.get());
        assertEquals(0, openingCalls.get());
        assertEquals(0, repository.writes);
        assertNull(repository.lastAttempt);
        assertTrue(Files.notExists(registryFile));

        assertThrows(NarrativeProviderException.class, () -> controller.finalizeSession(started.id(), confirmation));
        assertEquals(6, proposals.get());
        assertEquals(0, repository.writes);
        assertTrue(Files.exists(registryFile));
        InitializationSessionController.FinalizeResponse result = controller.finalizeSession(started.id(), confirmation);

        GameInstance<?> saved = repository.load(result.game().gameId()).orElseThrow();
        assertEquals("ap", saved.rulesetId());
        assertEquals("商队向导", saved.character().background());
        assertEquals(saved.character(), result.game().character());
        FileGameRepository disk = repository();
        disk.save(saved);
        assertEquals(saved.character(), disk.load(saved.gameId()).orElseThrow().character());
        assertEquals("冒险开始。", result.opening().narration());
        assertEquals(6, proposals.get());
        assertEquals(2, openingCalls.get());
        assertEquals(1, repository.writes);
        assertEquals("isekai", registry.world(saved.gameId()).path("template_id").asText());
    }

    /** 验证更新表单会淘汰旧版本并在写档失败后使用新角色生成开场。 */
    @Test
    void revisedFormInvalidatesOldConfirmation() {
        CountingRepository repository = new CountingRepository(null);
        NarrativeApplicationService narratives = new NarrativeApplicationService(repository, catalog,
                new DefaultNarrationService(prompt -> new NarrativeResponse(
                        prompt.snapshot().character().name() + "的冒险开始。", null, List.of("四处看看"))),
                new GameActionService(repository, catalog, engines));
        GameInitializationFlow flow = new GameInitializationFlow(new GameInitializationSchemaService(catalog, engines),
                new GameInstanceService(new WorldInitializationService(catalog), catalog, engines),
                narratives, new GameSaveService(repository));
        GameInitializationFlow.StartedSession started = flow.start(new GameSelection("isekai_grey", null));
        GameInitializationFlow.CheckedForm original = flow.validate(started.id(),
                Map.of("characterName", "凯尔", "age", 19, "gender", "未指定"), Map.of());
        assertNotNull(original.version());

        repository.failNextSave = true;
        assertThrows(GamePersistenceException.class, () -> flow.confirm(started.id(), original.version()));
        GameInstance<?> firstAttempt = repository.lastAttempt;
        GameInitializationFlow.CheckedForm invalid = flow.validate(started.id(),
                Map.of("characterName", "林舟", "age", 22), Map.of());
        assertNull(invalid.version());
        assertThrows(GameRequestException.class, () -> flow.confirm(started.id(), original.version()));

        GameInitializationFlow.CheckedForm revised = flow.validate(started.id(),
                Map.of("characterName", "林舟", "age", 22, "gender", "未指定", "background", "商队向导"), Map.of());
        assertThrows(GameRequestException.class, () -> flow.confirm(started.id(), original.version()));
        NarrativeApplicationService.NarratedTurn created = flow.confirm(started.id(), revised.version());
        assertEquals(firstAttempt.gameId(), created.instance().gameId());
        assertEquals("林舟的冒险开始。", created.narration());
        assertEquals(new CharacterProfile("林舟", 22, "未指定", "商队向导"), repository.saved.character());
        assertEquals(1, repository.writes);
        assertThrows(GameRequestException.class, () -> flow.validate(started.id(), Map.of(), Map.of()));
    }

    /** 验证引擎动作与世界更新共用一次 revision 和一次保存，错误动作不产生部分写入。 */
    @Test
    void actionAndUpdatesSaveExactlyOnceAndRejectStaleOrInvalidActions() {
        var initial = new GameInstanceService(new WorldInitializationService(catalog), catalog, engines)
                .initialize("combined", request("石桥镇"), "ap", Map.of(
                        "characterName", text("凯尔"), "age", new InitializationValue.IntegerNumber(16),
                        "gender", text("未指定"), "archetype", text("swordsman"), "difficulty", text("adventure")));
        var repository = new CountingRepository(initial);
        var action = new ActionIntent("rest", "", List.of());
        var responses = new ArrayDeque<>(List.of(new NarrativeResponse("你在古堡休息。", action, List.of("观察四周"),
                Map.of("location", text("古堡"), "region", text("ruins"))),
                new NarrativeResponse("你已经休息完毕。", null, List.of("观察四周"))));
        var turn = service(repository, responses).narrate("combined", 0, "休息", "zh-CN");
        assertEquals(1, turn.instance().revision());
        assertEquals(1, turn.instance().worldState().revision());
        assertEquals(initial.character(), turn.instance().character());
        assertEquals(1, repository.writes);
        assertFalse(turn.confirmedEvents().isEmpty());
        assertEquals("你已经休息完毕。", turn.narration());
        assertEquals(List.of("石桥镇", "古堡"), turn.instance().worldState().visitedLocations());
        var actions = new GameActionService(repository, catalog, engines);
        assertThrows(IllegalArgumentException.class, () -> actions.applyNarrativeTurn("combined", 0, null, Map.of("location", text("旧版本"))));
        assertThrows(IllegalArgumentException.class, () -> actions.applyNarrativeTurn("combined", 1,
                new ActionIntent("unsupported", "", List.of()), Map.of("location", text("错误地点"))));
        assertEquals(1, repository.writes);
        assertEquals("古堡", repository.saved.worldState().location());
        var narrative = new CountingRepository(GameInstance.narrativeOnly("no-engine", initial.worldState()));
        assertThrows(IllegalArgumentException.class, () -> new GameActionService(narrative, catalog, engines)
                .applyNarrativeTurn("no-engine", 0, action, Map.of("location", text("错误地点"))));
        assertEquals(0, narrative.writes);

        responses.add(new NarrativeResponse("你想休息。", action, List.of("观察四周")));
        responses.add(new NarrativeResponse("你又休息了一次。", action, List.of("观察四周")));
        NarrativeApplicationService.NarratedTurn fallback = service(repository, responses)
                .narrate("combined", 1, "再休息一会儿", "zh-CN");
        assertEquals(2, fallback.instance().revision());
        assertEquals(2, repository.writes);
        assertEquals(fallback.confirmedEvents().getFirst().outcome(), fallback.narration());
    }

    /** 验证访问地点能够跨世界副本和存档序列化保存。 */
    @Test
    void visitedLocationsSurviveWorldCopiesAndSerialization() throws Exception {
        var world = new WorldInitializationService(catalog).initialize(request("石桥镇"));
        var event = new TimedEvent("deadline", "plot", "线索", "解决", "错过", 1, 3, 2,
                TimedEvent.Status.ACTIVE, "");
        var copied = world.withRevision(1).schedule(event).resolveTimedEvent("deadline", "找到线索");
        assertEquals(List.of("石桥镇"), copied.visitedLocations());
        assertThrows(UnsupportedOperationException.class, () -> copied.visitedLocations().add("无法写入"));
        var repository = new CountingRepository(GameInstance.narrativeOnly("copies", world));
        var actions = new GameActionService(repository, catalog, engines);
        var priced = actions.setPriceModifier("copies", 0, new PriceModifier("all", 125));
        assertEquals(world.visitedLocations(), priced.worldState().visitedLocations());
        var removed = actions.applyWorldAction("copies", 1, new WorldAction.RemovePriceModifier(PriceModifier.Category.ALL));
        assertEquals(world.visitedLocations(), removed.worldState().visitedLocations());
        assertEquals(world.visitedLocations(), mapper.readValue(mapper.writeValueAsString(copied), WorldState.class).visitedLocations());
    }

    /** 验证声明数量与标识符边界在进入字段采纳之前被拒绝。 */
    @Test
    void updatesRejectInvalidIdentifiersAndExcessiveFields() {
        var updates = new java.util.LinkedHashMap<String, InitializationValue>();
        for (int i = 0; i < 17; i++) updates.put("field" + i, text("值"));
        assertThrows(NarrativeProviderException.class, () -> response(updates).validateUpdates());
        assertThrows(NarrativeProviderException.class, () -> response(Map.of("bad-field", text("值"))).validateUpdates());
    }

    /** 创建使用临时目录的真实 JSON 存档适配器。 */
    private FileGameRepository repository() {
        return new FileGameRepository(mapper, saves, new ClasspathGameStateTypeResolver());
    }

    /** 创建使用固定响应队列的完整叙事编排服务。
     * @param repository 测试存档端口
     * @param responses 依次返回的模型响应
     * @return 可结算并持久化的叙事服务
     */
    private NarrativeApplicationService service(GameRepository repository, ArrayDeque<NarrativeResponse> responses) {
        return new NarrativeApplicationService(repository, catalog, new DefaultNarrationService(prompt -> responses.removeFirst()),
                new GameActionService(repository, catalog, engines));
    }

    /** 构造包含初始地点的世界请求。
     * @param location 自由文本初始地点
     * @return 合法世界初始化请求
     */
    private static InitializeWorldRequest request(String location) {
        return new InitializeWorldRequest("isekai_grey", null, null, location, "town", null, null, null);
    }

    /** 构造带确定字段声明的有效叙事响应。
     * @param updates 本轮类型化字段声明
     * @return 四字段模型响应
     */
    private static NarrativeResponse response(Map<String, InitializationValue> updates) {
        return new NarrativeResponse("你观察着周围。", null, List.of("观察四周"), updates);
    }

    /** 将文本包装为协议值。
     * @param value 非空文本
     * @return 类型化文本值
     */
    private static InitializationValue.Text text(String value) { return new InitializationValue.Text(value); }

    /** 记录保存次数，验证同轮结算只有一次持久化。 */
    private static final class CountingRepository implements GameRepository {
        private GameInstance<?> saved;
        private GameInstance<?> lastAttempt;
        private boolean failNextSave;
        private int writes;
        /** 创建具有初始实例的存档桩。
         * @param initial 初始权威实例，尚无存档时为空
         */
        private CountingRepository(GameInstance<?> initial) { saved = initial; }
        /** 读取唯一存档。
         * @param gameId 待读取的游戏标识
         */
        @Override public Optional<GameInstance<?>> load(String gameId) {
            return Optional.<GameInstance<?>>ofNullable(saved).filter(instance -> instance.gameId().equals(gameId));
        }

        /** 记录本次保存尝试与成功写入次数。
         * @param instance 待保存的权威实例
         */
        @Override public void save(GameInstance<?> instance) {
            lastAttempt = instance;
            if (failNextSave) {
                failNextSave = false;
                throw new GamePersistenceException("模拟首次写档失败");
            }
            saved = instance;
            writes++;
        }
    }
}
