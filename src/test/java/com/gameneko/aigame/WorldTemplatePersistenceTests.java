package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.api.GameCatalogController;
import com.gameneko.aigame.application.*;
import com.gameneko.aigame.content.*;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineCatalog;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import com.gameneko.aigame.persistence.FileTemplateRepository;
import com.gameneko.aigame.persistence.FileWorldbookRepository;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ApState;
import com.gameneko.aigame.narrative.WorldDraftModel;
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

    /** 验证世界素材与引擎注册提案经落盘、重启后仍然完整。 */
    @Test
    void savedTemplateSurvivesReloadAndInitializesAp() {
        var templateFiles = new FileTemplateRepository(mapper, temporary.resolve("templates"));
        var worldFiles = new FileWorldbookRepository(mapper, temporary.resolve("worldbooks"));
        var catalog = catalog(worldFiles, templateFiles);
        var api = new GameCatalogController(catalog, catalog, new ClasspathGameEngineCatalog(), worldFiles, templateFiles);
        GameCatalogController.TemplateResponse saved = api.saveTemplate(new GameCatalogController.TemplateRequest(
                "moon_template", "双月", "isekai", List.of("双月", "生存"), "isekai"));
        assertEquals("moon_template", saved.templateId());
        var reloaded = catalog(worldFiles, templateFiles);
        var base = catalog.template("isekai");
        var template = reloaded.template(saved.templateId());
        assertEquals(base.materials().path("monster_types"), template.materials().path("monster_types"));
        assertEquals(base.registrations(), template.registrations());
        assertTrue(reloaded.availableTemplates().stream().anyMatch(item -> item.id().equals("moon_template")));
        AtomicInteger calls = new AtomicInteger();
        String draft = "# 双月港\n\n从封港之夜开始。";
        WorldbuildNarrationService worldbuild = new WorldbuildNarrationService(new WorldDraftModel((operation, instruction, input) ->
                switch (calls.incrementAndGet()) {
                    case 1 -> mapper.valueToTree(Map.of("draft", draft, "reply", "草案已生成。"));
                    case 2 -> mapper.valueToTree(Map.of("status", "PASS"));
                    case 3 -> mapper.valueToTree(Map.of("name", "双月港", "type", "isekai"));
                    default -> throw new AssertionError("unexpected model call");
                }, mapper), worldFiles, catalog, catalog);
        WorldbuildNarrationService.Session session = worldbuild.session("generated");
        WorldbuildNarrationService.Turn accepted = worldbuild.answer(session, "异世界");
        WorldbookDefinition generated = worldbuild.save(session, accepted.version());
        assertEquals(draft, catalog(worldFiles, templateFiles).worldBook(generated.id()).narrative().descriptionHint());
        assertEquals("isekai", generated.type());
        api.saveWorldbook(new GameCatalogController.WorldbookRequest("moon_world", "双月世界", "isekai", "moon_template", "hardcore",
                "双月之下的世界", List.of(), List.of(), List.of("魔法有代价"), Map.of()));
        var fresh = catalog(worldFiles, templateFiles);
        GameInstance<?> instance = new GameInstanceService(new WorldInitializationService(fresh), fresh, new ClasspathGameEngineFactory())
                .initialize("moon_game", InitializeWorldRequest.defaults("moon_world"), "ap", Map.of(
                        "characterName", new InitializationValue.Text("旅者"), "age", new InitializationValue.IntegerNumber(20),
                        "archetype", new InitializationValue.Text("mage"), "difficulty", new InitializationValue.Text("hard")));
        assertEquals("ap", instance.rulesetId());
        assertEquals(5L,
                assertInstanceOf(ApState.class, instance.rulesetState()).resources().copper());
        assertEquals("hardcore", fresh.worldBook("moon_world").tone());
    }

    /** 验证空白模板可复用，并支持纯叙事和使用默认难度的 AP 实例。 */
    @Test
    void blankTypeCreatesNarrativeWorldAndReusableEmptyTemplate() {
        var templateFiles = new FileTemplateRepository(mapper, temporary.resolve("templates"));
        var worldFiles = new FileWorldbookRepository(mapper, temporary.resolve("worldbooks"));
        var catalog = catalog(worldFiles, templateFiles);
        var api = new GameCatalogController(catalog, catalog, new ClasspathGameEngineCatalog(), worldFiles, templateFiles);
        api.saveTemplate(new GameCatalogController.TemplateRequest("neon", "霓虹", "cyberpunk", List.of("都市"), ""));
        api.saveWorldbook(new GameCatalogController.WorldbookRequest("neon_world", "霓虹城", "cyberpunk", "neon", "balanced",
                "巨型都市", List.of(), List.of(), List.of(), Map.of()));
        var fresh = catalog(worldFiles, templateFiles);
        var service = new GameInstanceService(new WorldInitializationService(fresh), fresh, new ClasspathGameEngineFactory());
        var instance = service.initialize("neon_game", InitializeWorldRequest.defaults("neon_world"), null,
                Map.of("characterName", new InitializationValue.Text("旅者"), "age", new InitializationValue.IntegerNumber(20)));
        assertNull(instance.rulesetId());
        assertEquals("neon", fresh.worldBook("neon_world").templateId());
        GameInstance<?> apInstance = service.initializeWithDefaults("neon_ap", InitializeWorldRequest.defaults("neon_world"), "ap");
        assertEquals("adventure", assertInstanceOf(ApState.class, apInstance.rulesetState()).difficulty());
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
        assertThrows(IllegalArgumentException.class, () -> api.saveWorldbook(request("bad_type_format", "Dying Gods", "", "balanced")));
        assertThrows(IllegalArgumentException.class, () -> api.saveWorldbook(request("bad_type", "noir", "isekai", "balanced")));
        assertThrows(WorldContentException.class, () -> api.saveWorldbook(request("missing", "isekai", "missing_template", "balanced")));
        assertThrows(WorldContentException.class, () -> catalog.template("../escape"));
        assertThrows(IllegalArgumentException.class, () -> templateFiles.readDocument("../escape"));
        assertTrue(worldFiles.available().isEmpty());
    }

    /** 验证自定义类型可保存和修改，并检查内置世界书的保护。 */
    @Test
    void worldbooksCanBeEditedAndDeletedButBuiltinsAreProtected() {
        var templateFiles = new FileTemplateRepository(mapper, temporary.resolve("templates"));
        var worldFiles = new FileWorldbookRepository(mapper, temporary.resolve("worldbooks"));
        var catalog = catalog(worldFiles, templateFiles);
        var api = new GameCatalogController(catalog, catalog, new ClasspathGameEngineCatalog(), worldFiles, templateFiles);
        api.saveWorldbook(new GameCatalogController.WorldbookRequest("custom_a", "测试世界", "dying-gods-wasteland", "", "balanced",
                "测试描述", List.of(), List.of(), List.of(), Map.of()));
        assertEquals("dying-gods-wasteland", catalog.worldBook("custom_a").type());
        assertThrows(IllegalArgumentException.class, () -> api.deleteWorldbook("cinder_continent"));
        assertThrows(IllegalArgumentException.class, () -> api.updateWorldbook("cinder_continent",
                new GameCatalogController.WorldbookRequest("cinder_continent", "改名", "isekai", "isekai", "balanced",
                        "", List.of(), List.of(), List.of(), Map.of())));
        assertEquals("custom_a", api.updateWorldbook("custom_a", new GameCatalogController.WorldbookRequest("custom_a",
                "新名字", "dying-gods_wasteland", "", "hardcore", "新描述", List.of(), List.of(), List.of(), Map.of())).worldbookId());
        assertEquals("dying-gods_wasteland", catalog.worldBook("custom_a").type());
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

}
