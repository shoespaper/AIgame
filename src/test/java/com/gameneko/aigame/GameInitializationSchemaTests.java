package com.gameneko.aigame;

import com.gameneko.aigame.application.GameInitializationSchemaService;
import com.gameneko.aigame.content.WorldBook;
import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameEngineCatalog;
import com.gameneko.aigame.engine.GameEngineDescriptor;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameInitializationSchemaTests {
    @Test
    void worldAndOptionalEngineFieldsAreComposed() {
        WorldDefinition definition = new WorldDefinition("world", "1", "name", "desc", 1,
                java.util.Map.of("town", List.of()), java.util.Map.of(), java.util.Map.of());
        var engineField = new InitializationField("dicePool", "Dice pool", InitializationField.ValueType.INTEGER,
                true, List.of(), 1L, 20L, "");
        WorldBookCatalog worlds = id -> new WorldBook(definition, new InitializationSchema(List.of(
                new InitializationField("name", "Name", InitializationField.ValueType.TEXT, true, List.of(), null, null, ""))));
        GameEngineCatalog engines = new GameEngineCatalog() {
            private final GameEngineDescriptor descriptor = new GameEngineDescriptor("dice", "1", "Dice", new InitializationSchema(List.of(engineField)));
            @Override public List<GameEngineDescriptor> available() { return List.of(descriptor); }
            @Override public GameEngineDescriptor engine(String id) { return descriptor; }
        };
        var schema = new GameInitializationSchemaService(worlds, engines).schema(new GameSelection("world", "dice"));
        assertEquals(List.of("name", "dicePool"), schema.fields().stream().map(InitializationField::id).toList());
    }

    @Test
    void duplicateWorldAndEngineFieldIsRejected() {
        WorldBookCatalog worlds = id -> new WorldBook(new WorldDefinition("world", "1", "name", "desc", 1,
                java.util.Map.of("town", List.of()), java.util.Map.of(), java.util.Map.of()),
                new InitializationSchema(List.of(new InitializationField("name", "Name", InitializationField.ValueType.TEXT, true, List.of(), null, null, ""))));
        GameEngineCatalog engines = new GameEngineCatalog() {
            @Override public List<GameEngineDescriptor> available() { return List.of(); }
            @Override public GameEngineDescriptor engine(String id) { return new GameEngineDescriptor(id, "1", id,
                    new InitializationSchema(List.of(new InitializationField("name", "Name", InitializationField.ValueType.TEXT, true, List.of(), null, null, "")))); }
        };
        assertThrows(IllegalArgumentException.class, () -> new GameInitializationSchemaService(worlds, engines)
                .schema(new GameSelection("world", "engine")));
    }

    @Test
    void worldBookDoesNotRequireEngineDifficulty() {
        WorldDefinition definition = new WorldDefinition("world", "1", "name", "desc", 1,
                java.util.Map.of("town", List.of()), java.util.Map.of("story", new WorldDefinition.DifficultyDefinition(1, 0, 0, 0, "Story", "")), java.util.Map.of());
        var ids = definition.initializationSchema().fields().stream().map(InitializationField::id).toList();
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains("gender"));
        org.junit.jupiter.api.Assertions.assertFalse(ids.contains("difficulty"));
    }
}
