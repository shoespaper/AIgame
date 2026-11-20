package com.gameneko.aigame;

import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GameEngineSelectionTests {
    @Test
    void noEngineCreatesNarrativeOnlyGame() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var service = new GameInstanceService(new WorldInitializationService(catalog), catalog);
        var game = service.initialize("story", InitializeWorldRequest.defaults("isekai_grey"));
        assertNull(game.rulesetId());
        assertNull(game.rulesetState());
    }

    @Test
    void apIsSelectedThroughEngineBoundary() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var service = new GameInstanceService(new WorldInitializationService(catalog), catalog);
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
        org.junit.jupiter.api.Assertions.assertTrue(runtime.validate(state, intent).accepted());
        var resolution = runtime.resolve(state, intent);
        org.junit.jupiter.api.Assertions.assertEquals("rest", resolution.events().getFirst().type());
    }
}
