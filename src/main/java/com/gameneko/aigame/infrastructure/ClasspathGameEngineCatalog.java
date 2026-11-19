package com.gameneko.aigame.infrastructure;

import com.gameneko.aigame.engine.GameEngineCatalog;
import com.gameneko.aigame.engine.GameEngineDescriptor;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/** Classpath registry for the game engines shipped by this application. */
@Component
public final class ClasspathGameEngineCatalog implements GameEngineCatalog {
    @Override
    public List<GameEngineDescriptor> available() { return List.of(descriptor()); }

    @Override
    public GameEngineDescriptor engine(String engineId) {
        if ("ap".equalsIgnoreCase(engineId)) return descriptor();
        throw new IllegalArgumentException("Unsupported game engine: " + engineId);
    }

    private static GameEngineDescriptor descriptor() {
        return new GameEngineDescriptor("ap", "baseline-1", "AP",
                new InitializationSchema(List.of(
                        new InitializationField("archetype", "职业", InitializationField.ValueType.TEXT,
                                true, List.of("swordsman", "mage", "ranger", "alchemist"), null, null, "请选择职业"),
                        new InitializationField("difficulty", "难度", InitializationField.ValueType.TEXT,
                                true, List.of(), null, null, "请选择难度"))));
    }
}
