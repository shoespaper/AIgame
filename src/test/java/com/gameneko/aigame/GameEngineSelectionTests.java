package com.gameneko.aigame;

import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import com.gameneko.aigame.persistence.FileTemplateRepository;
import com.gameneko.aigame.persistence.FileWorldbookRepository;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证引擎选择及 AP 初始化和动作结算的接入行为。 */
class GameEngineSelectionTests {
    /** 验证无模板的世界可使用 AP 默认难度创建角色并推进一天。
     * @param directory 用于保存测试世界书和模板的临时目录
     */
    @Test
    void apUsesEngineDefaultsWithoutWorldTemplate(@TempDir Path directory) {
        ObjectMapper mapper = new ObjectMapper();
        FileWorldbookRepository worldbooks = new FileWorldbookRepository(mapper, directory.resolve("worldbooks"));
        worldbooks.save(new WorldbookDefinition("custom", "1.0", "艾欧尼亚", "fantasy", "",
                "balanced", "", List.of(), List.of(), List.of(), Map.of()));
        ClasspathContentCatalog catalog = new ClasspathContentCatalog(mapper, new DefaultResourceLoader(),
                worldbooks, new FileTemplateRepository(mapper, directory.resolve("templates")));
        ClasspathGameEngineFactory engines = new ClasspathGameEngineFactory();
        GameInstanceService service = new GameInstanceService(new WorldInitializationService(catalog), catalog, engines);
        WorldDefinition world = catalog.world("custom");
        ApGameEngineRuntime runtime = new ApGameEngineRuntime(world);

        assertTrue(runtime.initializationPrompt().contains("adventure（冒险）"));
        GameInstance<?> game = service.initializeWithDefaults("ap-custom", InitializeWorldRequest.defaults("custom"), "ap");
        ApRulesetRuntime.ApState state = assertInstanceOf(ApRulesetRuntime.ApState.class, game.rulesetState());

        assertEquals("adventure", state.difficulty());
        assertEquals(new ApRulesetRuntime.Resources(15, 3, 2), state.resources());
        assertTrue(runtime.resolve(state, new ActionIntent("advance_day", "", List.of())).advancesDay());
    }

    @Test
    void noEngineCreatesNarrativeOnlyGame() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var service = new GameInstanceService(new WorldInitializationService(catalog), catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory());
        var game = service.initialize("story", InitializeWorldRequest.defaults("isekai_grey"), null, Map.of(
                "characterName", new InitializationValue.Text("旅人"),
                "age", new InitializationValue.IntegerNumber(16),
                "gender", new InitializationValue.Text("未定")));
        assertNull(game.rulesetId());
        assertNull(game.rulesetState());
    }

    @Test
    void apIsSelectedThroughEngineBoundary() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var service = new GameInstanceService(new WorldInitializationService(catalog), catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory());
        var game = service.initialize("ap", InitializeWorldRequest.defaults("isekai_grey"), "ap", Map.of(
                "characterName", new InitializationValue.Text("旅人"),
                "age", new InitializationValue.IntegerNumber(16),
                "gender", new InitializationValue.Text("未定"),
                "archetype", new InitializationValue.Text("swordsman"),
                "difficulty", new InitializationValue.Text("adventure")));
        assertInstanceOf(com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ApState.class, game.rulesetState());
    }

    @Test
    void apAdapterAcceptsSharedWorldBookInitializationFields() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var runtime = new ApGameEngineRuntime(catalog.world("isekai_grey"));
        var state = runtime.initialize(Map.of(
                "characterName", new InitializationValue.Text("测试角色"),
                "age", new InitializationValue.IntegerNumber(20),
                "gender", new InitializationValue.Text("未定"),
                "archetype", new InitializationValue.Text("swordsman"),
                "difficulty", new InitializationValue.Text("adventure")));
        org.junit.jupiter.api.Assertions.assertEquals("测试角色", state.characterName());
    }

    @Test
    void apAdapterValidatesAndResolvesGenericIntent() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var runtime = new ApGameEngineRuntime(catalog.world("isekai_grey"));
        var state = runtime.initialize(Map.of(
                "characterName", new InitializationValue.Text("测试角色"),
                "age", new InitializationValue.IntegerNumber(20),
                "gender", new InitializationValue.Text("未定"),
                "archetype", new InitializationValue.Text("swordsman"),
                "difficulty", new InitializationValue.Text("adventure")));
        var intent = new ActionIntent("rest", "", java.util.List.of());
        var resolution = runtime.resolve(state, intent);
        org.junit.jupiter.api.Assertions.assertEquals("rest", resolution.events().getFirst().type());
    }
}
