package com.gameneko.aigame;

import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.gameneko.aigame.engine.InitializationValue;
import java.util.Map;

class GameInstanceTests {
    @Test
    void createsInitializedGameEnvelope() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var worldService = new WorldInitializationService(catalog);
        var instance = new GameInstanceService(worldService, catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory()).initialize(
                "demo", com.gameneko.aigame.application.InitializeWorldRequest.defaults("isekai_grey"), "ap",
                Map.of("characterName", new InitializationValue.Text("旅人"),
                        "age", new InitializationValue.IntegerNumber(16),
                        "gender", new InitializationValue.Text("未定"),
                        "archetype", new InitializationValue.Text("swordsman"),
                        "difficulty", new InitializationValue.Text("adventure")));

        assertEquals("demo", instance.gameId());
        assertEquals("isekai_grey", instance.worldId());
        assertEquals("ap", instance.rulesetId());
        assertEquals("baseline-1", instance.rulesetVersion());
        assertEquals(com.gameneko.aigame.engine.GameInstance.Lifecycle.INITIALIZED, instance.lifecycle());
        assertEquals(0, instance.revision());
        assertNotNull(instance.rulesetState());
        org.junit.jupiter.api.Assertions.assertTrue(
                instance.rulesetState() instanceof com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ApState);
    }
}
