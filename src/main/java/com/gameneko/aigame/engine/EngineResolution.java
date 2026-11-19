package com.gameneko.aigame.engine;

import java.util.List;

/** Result of a validated engine-neutral intent. State changes remain owned by the engine. */
public record EngineResolution<S extends GameState>(S nextState, List<ConfirmedEvent> events) {
    public EngineResolution {
        if (nextState == null) throw new IllegalArgumentException("Next engine state is required");
        events = events == null ? List.of() : List.copyOf(events);
    }
}
