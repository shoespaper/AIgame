package com.gameneko.aigame;

import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GameInstanceTests {
    @Test
    void createsInitializedGameEnvelope() {
        var worldService = new WorldInitializationService(
                new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader()));
        var instance = new GameInstanceService(worldService).initialize(
                "demo", com.gameneko.aigame.application.InitializeWorldRequest.defaults("isekai_grey"));

        assertEquals("demo", instance.gameId());
        assertEquals("isekai_grey", instance.worldId());
        assertEquals("ap", instance.rulesetId());
        assertEquals("baseline", instance.rulesetVersion());
        assertEquals(com.gameneko.aigame.engine.GameInstance.Lifecycle.INITIALIZED, instance.lifecycle());
        assertEquals(0, instance.revision());
        assertNull(instance.rulesetState());
    }
}
