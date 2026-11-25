package com.gameneko.aigame;

import com.gameneko.aigame.narrative.NarrativeProviderException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.application.*;
import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.*;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import com.gameneko.aigame.infrastructure.ClasspathGameStateTypeResolver;
import com.gameneko.aigame.narrative.*;
import com.gameneko.aigame.persistence.FileGameRepository;
import com.gameneko.aigame.persistence.GamePersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

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

/** 验证确定字段声明在问答、世界状态、回合版本及真实存档之间的一致性。 */
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
                response(Map.of("location", text("古堡"), "region", text("ruins"), "name", text("新世界"))),
                response(Map.of("location", text("石桥镇"), "region", text("错误区域"), "time", text("黄昏"))),
                response(Map.of("visitedLocations", text("古堡"), "mainQuest", text("寻找钥匙")))));
        var service = service(repository, responses);
        var first = service.narrate("travel", 0, "去古堡", "zh-CN");
        assertEquals(1, first.instance().revision());
        assertEquals("ruins", first.instance().worldState().currentRegion());
        assertEquals("新世界", first.instance().worldState().setting());
        service.narrate("travel", 1, "回镇", "zh-CN");
        var last = service.narrate("travel", 2, "观察", "zh-CN");
        var loaded = repository.load("travel").orElseThrow();
        assertEquals(last.instance(), loaded);
        assertEquals(3, loaded.worldState().revision());
        assertEquals("石桥镇", loaded.worldState().location());
        assertEquals("ruins", loaded.worldState().currentRegion());
        assertEquals("黄昏", loaded.worldState().time());
        assertEquals("寻找钥匙", loaded.worldState().mainQuest());
        assertEquals(List.of("石桥镇", "古堡"), loaded.worldState().visitedLocations());
    }

    /** 验证只有非法区域、错误类型或未知字段时不写入世界，也不推进版本。 */
    @Test
    void invalidFieldsAreIgnoredIndependentlyAndOpeningCanSetLocation() {
        var repository = repository();
        WorldState world = new WorldInitializationService(catalog).initialize("isekai_grey");
        GameInstance<?> initial = GameInstance.narrativeOnly("opening", world);
        var responses = new ArrayDeque<>(List.of(
                response(Map.of("location", text("石桥镇"), "region", text("town"))),
                response(Map.of("region", text("石桥镇"), "weather", text("雨"), "season", text("冬"),
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
        InitializationNarrationService interview = new InitializationNarrationService(prompt -> response(Map.of(
                "characterName", text("凯尔"), "age", new InitializationValue.IntegerNumber(19))),
                new GameInitializationSchemaService(catalog, engines), catalog);
        NarrativeApplicationService narratives = new NarrativeApplicationService(repository, catalog,
                new DefaultNarrationService(prompt -> {
                    if (openingCalls.incrementAndGet() == 1) throw new NarrativeProviderException("模型暂时不可用", true);
                    return response(Map.of("location", text("石桥镇"), "region", text("town")));
                }), new GameActionService(repository, catalog, engines));
        GameInitializationFlow flow = new GameInitializationFlow(interview,
                new GameInstanceService(new WorldInitializationService(catalog), catalog, engines),
                narratives, new GameSaveService(repository));
        GameInitializationFlow.StartedSession started = flow.start(new GameSelection("isekai_grey", null));
        assertTrue(started.turn().done());

        assertThrows(NarrativeProviderException.class, () -> flow.confirm(started.id()));
        assertEquals(0, repository.writes);
        assertNull(repository.lastAttempt);

        repository.failNextSave = true;
        assertThrows(GamePersistenceException.class, () -> flow.confirm(started.id()));
        assertEquals(0, repository.writes);
        GameInstance<?> prepared = repository.lastAttempt;
        assertEquals(1, prepared.revision());

        Callable<NarrativeApplicationService.NarratedTurn> confirm = () -> flow.confirm(started.id());
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<NarrativeApplicationService.NarratedTurn>> responses =
                    executor.invokeAll(List.of(confirm, confirm), 5, TimeUnit.SECONDS);
            NarrativeApplicationService.NarratedTurn first = responses.getFirst().get();
            assertSame(first, responses.getLast().get());
            assertSame(first, flow.confirm(started.id()));
            assertSame(prepared, first.instance());
            assertEquals("石桥镇", repository.load(prepared.gameId()).orElseThrow().worldState().location());
            assertEquals(2, openingCalls.get());
            assertEquals(1, repository.writes);
        }
    }

    /** 验证引擎动作与世界更新共用一次 revision 和一次保存，错误动作不产生部分写入。 */
    @Test
    void actionAndUpdatesSaveExactlyOnceAndRejectStaleOrInvalidActions() {
        var initial = new GameInstanceService(new WorldInitializationService(catalog), catalog, engines)
                .initialize("combined", request("石桥镇"), "ap", Map.of(
                        "characterName", text("凯尔"), "age", new InitializationValue.IntegerNumber(16),
                        "archetype", text("swordsman"), "difficulty", text("adventure")));
        var repository = new CountingRepository(initial);
        var action = new ActionIntent("rest", "", List.of());
        var responses = new ArrayDeque<>(List.of(new NarrativeResponse("你在古堡休息。", action, List.of("观察四周"),
                Map.of("location", text("古堡"), "region", text("ruins")))));
        var turn = service(repository, responses).narrate("combined", 0, "休息", "zh-CN");
        assertEquals(1, turn.instance().revision());
        assertEquals(1, turn.instance().worldState().revision());
        assertEquals(1, repository.writes);
        assertFalse(turn.confirmedEvents().isEmpty());
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
    }

    /** 验证引擎提示模板进入 AI 多轮上下文，由 updates 一次多答，并按缺失字段反馈推进。
     * @throws Exception 提示捕获或服务响应失败
     */
    @Test
    void initializationUsesEnginePromptAndAiLedMultiturnCollection() throws Exception {
        var prompts = new java.util.ArrayList<NarrativePrompt>();
        var responses = new ArrayDeque<>(List.of(new NarrativeResponse("我们先聊聊你的角色。", null,
                        List.of("从角色经历开始"), Map.of()),
                response(Map.of("characterName", text("凯尔"), "age", text("十六"), "location", text("石桥镇"), "region", text("town"))),
                response(Map.of("characterName", text("莉娅"), "age", new InitializationValue.IntegerNumber(16),
                        "archetype", text("mage"), "difficulty", text("invalid"), "region", text("invalid"))),
                response(Map.of("difficulty", text("adventure")))));
        var service = new InitializationNarrationService(prompt -> { prompts.add(prompt); return responses.removeFirst(); },
                new GameInitializationSchemaService(catalog, engines), catalog);
        var session = service.session("init", new GameSelection("isekai_grey", "ap"));
        var opening = service.open(session);
        assertFalse(opening.done());
        assertEquals(List.of("从角色经历开始"), opening.options());
        assertTrue(prompts.getFirst().systemInstruction().contains("AP 规则游戏的开局访谈"));
        assertTrue(prompts.getFirst().systemInstruction().contains("characterName（角色名字）"));
        assertTrue(prompts.getFirst().systemInstruction().contains("difficulty（难度）"));
        assertTrue(prompts.getFirst().systemInstruction().contains("archetype（职业"));

        var first = service.answer(session, "我叫凯尔，十六岁，在石桥镇开始冒险");
        assertFalse(first.done());
        assertEquals("凯尔", first.collected().get("characterName"));
        assertFalse(first.collected().containsKey("age"));
        assertEquals("石桥镇", first.collected().get("location"));
        assertTrue(prompts.get(1).systemInstruction().contains("age（年龄）"));
        assertTrue(prompts.get(0).history().isEmpty());
        assertEquals("我们先聊聊你的角色。", prompts.get(1).history().getFirst().text());

        var second = service.answer(session, "改名莉娅，我是一位法师，普通难度");
        assertFalse(second.done());
        assertEquals("莉娅", second.collected().get("characterName"));
        assertEquals(16L, second.collected().get("age"));
        assertEquals("mage", second.collected().get("archetype"));
        assertFalse(second.collected().containsKey("difficulty"));
        assertEquals("town", second.collected().get("region"));

        var ready = service.answer(session, "adventure");
        assertTrue(ready.done());
        assertTrue(prompts.get(3).systemInstruction().contains("difficulty：field_option_invalid"));
        assertTrue(prompts.get(3).systemInstruction().contains("已收集字段"));
        assertEquals("莉娅", ready.collected().get("characterName"));
        assertEquals("石桥镇", service.request(session).location());
        assertEquals("town", service.request(session).region());
        assertEquals(text("adventure"), service.answers(session).get("difficulty"));
    }

    /** 验证未选引擎时服务端使用纯叙事默认模板且只等待世界书必填字段。
     * @throws Exception 提示捕获失败
     */
    @Test
    void narrativeOnlyInitializationUsesDefaultPromptAndWorldbookSchema() throws Exception {
        var prompts = new java.util.ArrayList<NarrativePrompt>();
        var responses = new ArrayDeque<>(List.of(new NarrativeResponse("告诉我角色名字和年龄。"),
                response(Map.of("characterName", text("凯尔"), "age", new InitializationValue.IntegerNumber(19)))));
        var service = new InitializationNarrationService(prompt -> { prompts.add(prompt); return responses.removeFirst(); },
                new GameInitializationSchemaService(catalog, engines), catalog);
        var session = service.session("story-init", new GameSelection("isekai_grey", null));
        assertFalse(service.open(session).done());
        assertTrue(prompts.getFirst().systemInstruction().contains("自然的多轮开局访谈"));
        assertFalse(prompts.getFirst().systemInstruction().contains("AP 规则游戏"));
        var ready = service.answer(session, "凯尔，十九岁");
        assertTrue(ready.done());
        assertEquals("凯尔", ready.collected().get("characterName"));
        assertEquals(19L, ready.collected().get("age"));
    }

    /** 验证缺失必填字段时通过上下文继续访谈，AI 声明的补充场景写入世界请求。
     */
    @Test
    void missingDeclarationsStayPendingAndAiCanDeclareOpeningScene() {
        var responses = new ArrayDeque<>(List.of(new NarrativeResponse("我先了解角色信息。"),
                new NarrativeResponse("还需要名字和年龄。"),
                response(Map.of("characterName", text("凯尔"), "age", new InitializationValue.IntegerNumber(16),
                        "originScene", text("我在寻找失散的家人")))));
        var service = new InitializationNarrationService(prompt -> responses.removeFirst(),
                new GameInitializationSchemaService(catalog, engines), catalog);
        var session = service.session("fallback", new GameSelection("isekai_grey", null));
        assertFalse(service.open(session).done());
        assertFalse(service.answer(session, "我叫凯尔").done());
        assertTrue(service.answer(session, "凯尔，16岁，我在寻找失散的家人").done());
        assertEquals("我在寻找失散的家人", service.request(session).originScene());
        assertTrue(service.ready(session));
    }

    /** 验证可选字段声明被拒绝时保留已满足的必填状态，且不阻止进入下一步。
     */
    @Test
    void invalidOptionalFieldDoesNotBlockCompletedRequiredFields() {
        var service = new InitializationNarrationService(prompt -> response(Map.of(
                "characterName", text("凯尔"), "age", new InitializationValue.IntegerNumber(16),
                "region", text("not-a-region"))), new GameInitializationSchemaService(catalog, engines), catalog);
        var session = service.session("optional-error", new GameSelection("isekai_grey", null));
        var turn = service.open(session);
        assertTrue(turn.done());
        assertEquals("凯尔", turn.collected().get("characterName"));
        assertFalse(turn.collected().containsKey("region"));
    }

    /** 验证访问记录在世界复制、事件处理与价格修改后仍被保留，且旧 JSON 缺失该字段时可读取。 */
    @Test
    void visitedLocationsSurviveWorldCopiesAndOlderSaves() throws Exception {
        var world = new WorldInitializationService(catalog).initialize(request("石桥镇"));
        var event = new TimedEvent("deadline", "plot", "线索", "解决", "错过", 1, 3, 2,
                TimedEvent.Status.ACTIVE, WorldEffects.none());
        var copied = world.withRevision(1).schedule(event).resolveTimedEvent("deadline", "找到线索");
        assertEquals(List.of("石桥镇"), copied.visitedLocations());
        assertThrows(UnsupportedOperationException.class, () -> copied.visitedLocations().add("无法写入"));
        var repository = new CountingRepository(GameInstance.narrativeOnly("copies", world));
        var actions = new GameActionService(repository, catalog, engines);
        var priced = actions.setPriceModifier("copies", 0, new PriceModifier("all", 125));
        assertEquals(world.visitedLocations(), priced.worldState().visitedLocations());
        var removed = actions.applyWorldAction("copies", 1, new WorldAction.RemovePriceModifier(PriceModifier.Category.ALL));
        assertEquals(world.visitedLocations(), removed.worldState().visitedLocations());
        var json = mapper.valueToTree(world);
        ((ObjectNode) json).remove("visitedLocations");
        ((ObjectNode) json).put("weather", "旧天气");
        ((ObjectNode) json).put("season", "旧季节");
        assertEquals(List.of(), mapper.treeToValue(json, WorldState.class).visitedLocations());
        assertFalse(mapper.valueToTree(mapper.treeToValue(json, WorldState.class)).has("weather"));
        assertFalse(mapper.valueToTree(mapper.treeToValue(json, WorldState.class)).has("season"));
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
            return Optional.ofNullable(saved).filter(instance -> instance.gameId().equals(gameId));
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
