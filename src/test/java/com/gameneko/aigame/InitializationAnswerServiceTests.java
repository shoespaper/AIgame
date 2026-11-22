package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.InitializationAnswerService;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationValue;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InitializationAnswerServiceTests {
    @Test
    void parsesTypedAnswersAndReportsUnknownFields() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var service = new InitializationAnswerService(catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory());
        var result = service.parseAndValidate(new GameSelection("isekai_grey", null),
                "characterName=小明,age=20,energy=10");
        assertFalse(result.accepted());
        assertEquals("field_not_supported", result.errors().get("energy"));
        assertInstanceOf(InitializationValue.IntegerNumber.class, result.answers().get("age"));
    }

    @Test
    void missingRequiredAnswersPreventInstanceCreation() {
        var catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
        var service = new GameInstanceService(new WorldInitializationService(catalog), catalog);
        var request = InitializeWorldRequest.defaults("isekai_grey");
        assertThrows(IllegalArgumentException.class, () -> service.initialize("missing", request, null,
                Map.of("characterName", new InitializationValue.Text("小明"))));
    }
}
