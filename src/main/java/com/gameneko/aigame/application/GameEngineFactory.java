package com.gameneko.aigame.application;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameEngineRuntime;

import java.util.Objects;

/** Creates the selected engine for a specific world without exposing engine types to orchestration. */
@FunctionalInterface
public interface GameEngineFactory {
    GameEngineRuntime<?, ?> create(String engineId, WorldDefinition world);

    static GameEngineFactory unsupported() {
        return (engineId, world) -> { throw new IllegalArgumentException("Unsupported game engine: " + engineId); };
    }
}
