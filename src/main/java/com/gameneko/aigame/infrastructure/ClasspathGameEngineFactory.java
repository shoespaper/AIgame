package com.gameneko.aigame.infrastructure;

import com.gameneko.aigame.application.GameEngineFactory;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime;
import org.springframework.stereotype.Component;

/** Runtime registry for the engines currently shipped by the application. */
@Component
public final class ClasspathGameEngineFactory implements GameEngineFactory {
    @Override
    public GameEngineRuntime<?, ?> create(String engineId, WorldDefinition world) {
        if (engineId == null || engineId.isBlank()) throw new IllegalArgumentException("Game engine is not selected");
        return switch (engineId.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "ap" -> new ApGameEngineRuntime(world);
            default -> throw new IllegalArgumentException("Unsupported game engine: " + engineId);
        };
    }
}
