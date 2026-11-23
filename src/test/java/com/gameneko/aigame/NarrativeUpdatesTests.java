package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.*;
import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.*;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import com.gameneko.aigame.infrastructure.ClasspathGameStateTypeResolver;
import com.gameneko.aigame.narrative.*;
import com.gameneko.aigame.persistence.FileGameRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                response(Map.of("location", text("古堡"), "region", text("ruins"), "weather", text("雨"), "name", text("新世界"))),
                response(Map.of("location", text("石桥镇"), "region", text("错误区域"), "time", text("黄昏"))),
                response(Map.of("visitedLocations", text("古堡"), "weather", text("晴"), "mainQuest", text("寻找钥匙")))));
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
        assertEquals("晴", loaded.worldState().weather());
        assertEquals("寻找钥匙", loaded.worldState().mainQuest());
        assertEquals(List.of("石桥镇", "古堡"), loaded.worldState().visitedLocations());
    }

    /** 验证只有非法区域、错误类型或未知字段时不写入世界，也不推进版本。 */
    @Test
    void invalidFieldsAreIgnoredIndependentlyAndOpeningCanSetLocation() {
        var repository = repository();
        var world = new WorldInitializationService(catalog).initialize("isekai_grey");
        repository.save(GameInstance.narrativeOnly("opening", world));
        var responses = new ArrayDeque<>(List.of(
                response(Map.of("location", text("石桥镇"), "region", text("town"))),
                response(Map.of("region", text("石桥镇"), "weather", new InitializationValue.Flag(true), "unknown", text("无效")))));
        var service = service(repository, responses);
        var opening = service.opening("opening", 0, "zh-CN");
        assertEquals(1, opening.instance().revision());
        assertEquals(List.of("石桥镇"), opening.instance().worldState().visitedLocations());
        var turn = service.narrate("opening", 1, "观察", "zh-CN");
        assertEquals(opening.instance(), turn.instance());
        assertEquals("town", turn.instance().worldState().currentRegion());
        assertNull(turn.instance().worldState().weather());
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

    /** 验证初始化由服务端选择问题，声明可一次多答和修改，非法枚举及类型独立忽略。 */
    @Test
    void initializationCollectsAndOverwritesTypedUpdatesWithServerQuestions() {
        var responses = new ArrayDeque<>(List.of(new NarrativeResponse("请确认资料。"),
                response(Map.of("characterName", text("凯尔"), "age", text("十六"), "location", text("石桥镇"), "region", text("town"))),
                response(Map.of("characterName", text("莉娅"), "age", new InitializationValue.IntegerNumber(16),
                        "archetype", text("mage"), "difficulty", text("invalid"), "region", text("invalid"))),
                response(Map.of("difficulty", text("adventure"))),
                response(Map.of("characterName", text("最终名"), "weather", text("晴")))));
        var service = new InitializationNarrationService(prompt -> responses.removeFirst(),
                new GameInitializationSchemaService(catalog, engines), catalog);
        var session = service.session("init", new GameSelection("isekai_grey", "ap"));
        assertEquals("characterName", service.open(session).question().fieldId());
        var name = service.answer(session, "凯尔，住在石桥镇");
        assertEquals("age", name.question().fieldId());
        assertFalse(name.collected().containsKey("age"));
        var age = service.answer(session, "16岁，改名莉娅，法师");
        assertEquals("difficulty", age.question().fieldId());
        assertEquals("莉娅", age.collected().get("characterName"));
        assertEquals("town", age.collected().get("region"));
        assertEquals("mage", age.collected().get("archetype"));
        assertFalse(age.collected().containsKey("difficulty"));
        var supplement = service.answer(session, "冒险");
        assertEquals("__world_supplement", supplement.question().fieldId());
        assertFalse(supplement.done());
        assertTrue(service.answer(session, "晴天，改名最终名").done());
        assertEquals(text("最终名"), service.answers(session).get("characterName"));
        assertEquals("石桥镇", service.request(session).location());
        assertEquals("晴", service.request(session).weather());
    }

    /** 验证模型未声明字段时不猜测原文，非结构化补充仍进入开场场景。 */
    @Test
    void missingDeclarationsKeepQuestionAndUnstructuredSupplementIsPreserved() {
        var responses = new ArrayDeque<>(List.of(new NarrativeResponse("开始确认。"), new NarrativeResponse("收到。"),
                response(Map.of("characterName", text("凯尔"), "age", new InitializationValue.IntegerNumber(16))),
                new NarrativeResponse("已记下你的故事。")));
        var service = new InitializationNarrationService(prompt -> responses.removeFirst(),
                new GameInitializationSchemaService(catalog, engines), catalog);
        var session = service.session("fallback", new GameSelection("isekai_grey", null));
        service.open(session);
        assertEquals("characterName", service.answer(session, "凯尔").question().fieldId());
        service.answer(session, "凯尔，16岁");
        service.answer(session, "我在寻找失散的家人");
        assertEquals("我在寻找失散的家人", service.request(session).originScene());
        assertTrue(service.ready(session));
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
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("visitedLocations");
        assertEquals(List.of(), mapper.treeToValue(json, WorldState.class).visitedLocations());
    }

    /** 验证声明数量与标识符边界在进入字段采纳之前被拒绝。 */
    @Test
    void updatesRejectInvalidIdentifiersAndExcessiveFields() {
        var updates = new java.util.LinkedHashMap<String, InitializationValue>();
        for (int i = 0; i < 17; i++) updates.put("field" + i, text("值"));
        assertThrows(IllegalArgumentException.class, () -> response(updates).validateUpdates());
        assertThrows(IllegalArgumentException.class, () -> response(Map.of("bad-field", text("值"))).validateUpdates());
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
        return new InitializeWorldRequest("isekai_grey", null, null, location, "town", null, null, null, null,
                null, "凯尔", 16, "未定", "swordsman", "adventure");
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
        private int writes;
        /** 创建具有初始实例的存档桩。
         * @param initial 初始权威实例
         */
        private CountingRepository(GameInstance<?> initial) { saved = initial; }
        /** 读取唯一存档。
         * @param gameId 待读取的游戏标识
         */
        @Override public Optional<GameInstance<?>> load(String gameId) { return Optional.of(saved); }
        /** 记录本次写入及保存结果。
         * @param instance 待保存的权威实例
         */
        @Override public void save(GameInstance<?> instance) { saved = instance; writes++; }
    }
}
