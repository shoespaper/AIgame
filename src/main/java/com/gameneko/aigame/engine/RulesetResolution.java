package com.gameneko.aigame.engine;

import java.util.List;
import java.util.Objects;

/** The ruleset's authoritative next state and the confirmed outcomes for narration/audit. */
public record RulesetResolution<S extends GameState, E>(S nextState, List<E> events) {
    public RulesetResolution {
        Objects.requireNonNull(nextState, "nextState");
        events = List.copyOf(events);
    }
}
