package com.gameneko.aigame.engine;

/** Ruleset contract consumed by application use cases. */
public interface RulesetRuntime<S extends GameState, A, E, P> {
    String id();

    String version();

    ActionValidation validate(S state, A action);

    RulesetResolution<S, E> resolve(S state, A action);

    P project(S state);
}
