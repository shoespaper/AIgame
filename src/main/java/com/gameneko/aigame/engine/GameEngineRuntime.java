package com.gameneko.aigame.engine;

import java.util.Map;

/** Minimal contract for an optional, typed game engine selected at game creation. */
public interface GameEngineRuntime<S extends GameState, P> {
    String id();

    String version();

    InitializationSchema initializationSchema();

    S initialize(Map<String, InitializationValue> answers);

    ActionValidation validate(S state, ActionIntent intent);

    EngineResolution<S> resolve(S state, ActionIntent intent);

    P project(S state);
}
