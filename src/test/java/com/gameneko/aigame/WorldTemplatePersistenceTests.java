package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.api.GameCatalogController;
import com.gameneko.aigame.application.*;
import com.gameneko.aigame.content.*;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineCatalog;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.persistence.FileTemplateRepository;
import com.gameneko.aigame.persistence.FileWorldbookRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 验证自定义模板和世界书在隔离目录落盘、重启读取以及引擎初始化。 */
class WorldTemplatePersistenceTests {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 验证完整规则骨架、词缀和创建默认配置经落盘、重启后仍然完整。 */
    @Test
    void savedTemplateSurvivesReloadAndInitializesAp() {
        var templateFiles = new FileTemplateRepository(mapper, temporary.resolve("templates"));
        var worldFiles = new FileWorldbookRepository(mapper, temporary.resolve("worldbooks"));
        var catalog = catalog(worldFiles, templateFiles);
        var api = new GameCatalogController(catalog, catalog, new ClasspathGameEngineCatalog(), worldFiles, templateFiles);
        var saved = api.saveTemplate(new GameCatalogController.TemplateRequest("moon_template", "双月", "isekai", List.of("双月", "生存"),
                "isekai", "hardcore", "双月之下的世界", List.of(Map.of("type", "联盟", "desc_hint", "守护者")),
                List.of("流亡者"), List.of("魔法有代价")));
        assertEquals("moon_template", saved.templateId());
        var reloaded = catalog(worldFiles, templateFiles);
        var base = catalog.template("isekai");
        var template = reloaded.template(saved.templateId());
        assertEquals(base.monsters(), template.monsters());
        assertEquals(base.difficulties(), template.difficulties());
        assertEquals(base.abilities(), template.abilities());
        assertEquals(base.modifierPool(), template.modifierPool());
        assertFalse(template.modifierPool().isEmpty());
        assertEquals(List.of("魔法有代价"), template.creationDefaults().get("world_tropes"));
        assertEquals(7, template.npcTypes().size());
        assertTrue(template.npcTypes().values().stream().allMatch(npc -> !npc.bondPool().isEmpty()));
        assertTrue(template.regions().values().stream().allMatch(region -> !region.descriptionHint().isBlank()));
        assertTrue(reloaded.availableTemplates().stream().anyMatch(item -> item.id().equals("moon_template")));
        AtomicInteger calls = new AtomicInteger();
        var worldbuild = new WorldbuildNarrationService(prompt -> calls.incrementAndGet() == 1
                ? response() : response(update("type", "异世界")), mapper, reloaded);
        var session = worldbuild.session("reused", "moon_template");
        var reused = worldbuild.answer(session, "异世界");
        assertEquals("moon_template", reused.templateId());
        assertEquals(List.of("魔法有代价"), reused.collected().get("world_tropes"));
        assertEquals(List.of("流亡者"), reused.collected().get("origin_contexts"));
        assertFalse(reused.collected().containsKey("tone"));
        api.saveWorldbook(new GameCatalogController.WorldbookRequest("moon_world", "双月世界", "isekai", "moon_template", "hardcore",
                "双月之下的世界", List.of(), List.of(), List.of("魔法有代价"), Map.of()));
        var fresh = catalog(worldFiles, templateFiles);
        var instance = new GameInstanceService(new WorldInitializationService(fresh), fresh, new ClasspathGameEngineFactory())
                .initialize("moon_game", InitializeWorldRequest.defaults("moon_world"), "ap", Map.of(
                        "characterName", new InitializationValue.Text("旅者"), "age", new InitializationValue.IntegerNumber(20),
                        "archetype", new InitializationValue.Text("mage"), "difficulty", new InitializationValue.Text("adventure")));
        assertEquals("ap", instance.rulesetId());
        assertEquals("hardcore", fresh.worldBook("moon_world").tone());
    }

    /** 验证无内置模板的世界类型能够保存为空白模板并创建纯叙事实例。 */
    @Test
    void blankTypeCreatesNarrativeWorldAndReusableEmptyTemplate() {
        var templateFiles = new FileTemplateRepository(mapper, temporary.resolve("templates"));
        var worldFiles = new FileWorldbookRepository(mapper, temporary.resolve("worldbooks"));
        var catalog = catalog(worldFiles, templateFiles);
        var api = new GameCatalogController(catalog, catalog, new ClasspathGameEngineCatalog(), worldFiles, templateFiles);
        api.saveTemplate(new GameCatalogController.TemplateRequest("neon", "霓虹", "cyberpunk", List.of("都市"), "", "balanced",
                "巨型都市", List.of(), List.of(), List.of()));
        api.saveWorldbook(new GameCatalogController.WorldbookRequest("neon_world", "霓虹城", "cyberpunk", "neon", "balanced",
                "巨型都市", List.of(), List.of(), List.of(), Map.of()));
        var fresh = catalog(worldFiles, templateFiles);
        var service = new GameInstanceService(new WorldInitializationService(fresh), fresh, new ClasspathGameEngineFactory());
        var instance = service.initialize("neon_game", InitializeWorldRequest.defaults("neon_world"), null,
                Map.of("characterName", new InitializationValue.Text("旅者"), "age", new InitializationValue.IntegerNumber(20)));
        assertNull(instance.rulesetId());
        assertEquals("neon", fresh.worldBook("neon_world").templateId());
        assertThrows(IllegalArgumentException.class, () -> new ClasspathGameEngineFactory().create("ap", fresh.world("neon_world")));
        api.saveWorldbook(new GameCatalogController.WorldbookRequest("no_template", "黑街", "noir", "", "light", "侦探故事", List.of(), List.of(), List.of(), Map.of()));
        assertNotNull(new WorldInitializationService(fresh).initialize("no_template"));
    }

    /** 验证 API 校验类型、基调、模板引用并禁止覆盖内置内容。 */
    @Test
    void creationRejectsInvalidReferencesTraversalAndReservedIds() {
        var templateFiles = new FileTemplateRepository(mapper, temporary.resolve("templates"));
        var worldFiles = new FileWorldbookRepository(mapper, temporary.resolve("worldbooks"));
        var catalog = catalog(worldFiles, templateFiles);
        var api = new GameCatalogController(catalog, catalog, new ClasspathGameEngineCatalog(), worldFiles, templateFiles);
        assertThrows(IllegalArgumentException.class, () -> api.saveWorldbook(request("cinder_continent", "isekai", "isekai", "balanced")));
        assertThrows(IllegalArgumentException.class, () -> api.saveWorldbook(request("../escape", "isekai", "isekai", "balanced")));
        assertThrows(IllegalArgumentException.class, () -> api.saveWorldbook(request("bad_tone", "isekai", "isekai", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> api.saveWorldbook(request("bad_type", "noir", "isekai", "balanced")));
        assertThrows(WorldContentException.class, () -> api.saveWorldbook(request("missing", "isekai", "missing_template", "balanced")));
        assertThrows(WorldContentException.class, () -> catalog.template("../escape"));
        assertThrows(IllegalArgumentException.class, () -> templateFiles.readDocument("../escape"));
        assertTrue(worldFiles.available().isEmpty());
    }

    /** 验证自定义世界书可编辑删除，内置世界书受保护。 */
    @Test
    void worldbooksCanBeEditedAndDeletedButBuiltinsAreProtected() {
        var templateFiles = new FileTemplateRepository(mapper, temporary.resolve("templates"));
        var worldFiles = new FileWorldbookRepository(mapper, temporary.resolve("worldbooks"));
        var catalog = catalog(worldFiles, templateFiles);
        var api = new GameCatalogController(catalog, catalog, new ClasspathGameEngineCatalog(), worldFiles, templateFiles);
        api.saveWorldbook(new GameCatalogController.WorldbookRequest("custom_a", "测试世界", "isekai", "isekai", "balanced",
                "测试描述", List.of(), List.of(), List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> api.deleteWorldbook("cinder_continent"));
        assertThrows(IllegalArgumentException.class, () -> api.updateWorldbook("cinder_continent",
                new GameCatalogController.WorldbookRequest("cinder_continent", "改名", "isekai", "isekai", "balanced",
                        "", List.of(), List.of(), List.of(), Map.of())));
        assertEquals("custom_a", api.updateWorldbook("custom_a", new GameCatalogController.WorldbookRequest("custom_a",
                "新名字", "isekai", "isekai", "hardcore", "新描述", List.of(), List.of(), List.of(), Map.of())).worldbookId());
        assertEquals("新名字", catalog.worldBook("custom_a").narrative().name());
        api.deleteWorldbook("custom_a");
        assertTrue(worldFiles.available().isEmpty());
    }

    /** 创建使用隔离用户目录的目录实例。
     * @param worlds 世界书仓储
     * @param templates 模板仓储
     * @return 可重新加载的组合目录
     */
    private ClasspathContentCatalog catalog(FileWorldbookRepository worlds, FileTemplateRepository templates) {
        return new ClasspathContentCatalog(mapper, new DefaultResourceLoader(), worlds, templates);
    }

    /** 构造用于拒绝路径和枚举错误的世界书请求。
     * @param id 世界书标识
     * @param type 世界类型
     * @param templateId 引用模板
     * @param tone 基调
     * @return 世界书请求
     */
    private GameCatalogController.WorldbookRequest request(String id, String type, String templateId, String tone) {
        return new GameCatalogController.WorldbookRequest(id, "测试", type, templateId, tone, "", List.of(), List.of(), List.of(), Map.of());
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
}
