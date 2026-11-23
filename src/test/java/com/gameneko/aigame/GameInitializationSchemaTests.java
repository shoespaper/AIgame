package com.gameneko.aigame;

import com.gameneko.aigame.application.GameEngineFactory;
import com.gameneko.aigame.application.GameInitializationSchemaService;
import com.gameneko.aigame.content.WorldBook;
import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.EngineResolution;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GameInitializationSchemaTests {
    @Test
    void worldAndOptionalEngineFieldsAreComposed() {
        WorldDefinition definition = new WorldDefinition("world", "1", "name", "desc", 1,
                Map.of("town", List.of()), Map.of(), Map.of());
        var engineField = new InitializationField("dicePool", "Dice pool", InitializationField.ValueType.INTEGER,
                true, List.of(), 1L, 20L, "");
        WorldBook book = new WorldBook(definition, new InitializationSchema(List.of(
                new InitializationField("name", "Name", InitializationField.ValueType.TEXT, true, List.of(), null, null, ""))));
        WorldBookCatalog worlds = new WorldBookCatalog() {
            @Override public List<WorldBook> available() { return List.of(book); }
            @Override public WorldBook worldBook(String worldId) { return book; }
        };
        var schema = new GameInitializationSchemaService(worlds, engineFactory(engineField)).schema(new GameSelection("world", "dice"));
        assertEquals(List.of("name", "dicePool"), schema.fields().stream().map(InitializationField::id).toList());
    }

    @Test
    void duplicateWorldAndEngineFieldUsesEngineDefinition() {
        WorldBook book = new WorldBook(new WorldDefinition("world", "1", "name", "desc", 1,
                Map.of("town", List.of()), Map.of(), Map.of()),
                new InitializationSchema(List.of(new InitializationField("name", "Name", InitializationField.ValueType.TEXT, true, List.of(), null, null, ""))));
        WorldBookCatalog worlds = new WorldBookCatalog() {
            @Override public List<WorldBook> available() { return List.of(book); }
            @Override public WorldBook worldBook(String worldId) { return book; }
        };
        InitializationField engineField = new InitializationField("name", "Engine name", InitializationField.ValueType.INTEGER,
                true, List.of(), 1L, 20L, "engine prompt");
        GameInitializationSchemaService service = new GameInitializationSchemaService(worlds, engineFactory(engineField));
        GameInitializationSchemaService.InitializationProfile profile = service.profile(new GameSelection("world", "engine"));
        assertEquals("Engine name", profile.schema().fields().getFirst().label());
        assertEquals(InitializationField.ValueType.INTEGER, profile.schema().fields().getFirst().type());
        assertFalse(profile.worldSchema().fields().stream().anyMatch(field -> field.id().equals("name")));
    }

    @Test
    void worldBookDoesNotRequireEngineDifficulty() {
        WorldDefinition definition = new WorldDefinition("world", "1", "name", "desc", 1,
                Map.of("town", List.of()), Map.of("story", new WorldDefinition.DifficultyDefinition(1, 0, 0, 0, "Story", "")), Map.of());
        var ids = definition.initializationSchema().fields().stream().map(InitializationField::id).toList();
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains("gender"));
        org.junit.jupiter.api.Assertions.assertFalse(ids.contains("difficulty"));
    }

    /** 验证 AP 引擎的难度选项来自世界书定义，且与 finalize 阶段一致（非空）。 */
    @Test
    void apEngineDifficultyOptionsComeFromWorld() {
        var catalog = new com.gameneko.aigame.content.ClasspathContentCatalog(new com.fasterxml.jackson.databind.ObjectMapper(),
                new org.springframework.core.io.DefaultResourceLoader());
        var schema = new GameInitializationSchemaService(catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory())
                .schema(new GameSelection("isekai_grey", "ap"));
        var difficulty = schema.fields().stream().filter(field -> field.id().equals("difficulty")).findFirst().orElseThrow();
        assertFalse(difficulty.options().isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(difficulty.options().contains("adventure"));
    }

    /** 构造一个只声明单个字段的引擎工厂桩，用于验证 schema 组合逻辑。 */
    /** @param field 引擎声明的字段 */
    private static GameEngineFactory engineFactory(InitializationField field) {
        return (id, world) -> new GameEngineRuntime<GameState, Object>() {
            @Override public String id() { return id; }
            @Override public String version() { return "1"; }
            @Override public String initializationPrompt() { return "test engine setup prompt"; }
            @Override public Map<String, InitializationValue> defaultInitializationAnswers() { return Map.of("dicePool", new InitializationValue.IntegerNumber(1)); }
            @Override public InitializationSchema initializationSchema() { return new InitializationSchema(List.of(field)); }
            @Override public GameState initialize(Map<String, InitializationValue> answers) { throw new UnsupportedOperationException(); }
            @Override public ActionValidation validate(GameState state, ActionIntent intent) { throw new UnsupportedOperationException(); }
            @Override public EngineResolution<GameState> resolve(GameState state, ActionIntent intent) { throw new UnsupportedOperationException(); }
            @Override public Object project(GameState state) { throw new UnsupportedOperationException(); }
        };
    }
}
