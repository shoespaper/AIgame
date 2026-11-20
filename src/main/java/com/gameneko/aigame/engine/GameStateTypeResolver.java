package com.gameneko.aigame.engine;

/** Resolves a persisted engine id to its concrete typed state class. */
@FunctionalInterface
public interface GameStateTypeResolver {
    Class<?> stateType(String engineId);
}
