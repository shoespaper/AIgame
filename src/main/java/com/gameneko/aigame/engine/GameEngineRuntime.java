package com.gameneko.aigame.engine;

import java.util.Map;

/** Minimal contract for an optional, typed game engine selected at game creation. */
public interface GameEngineRuntime<S extends GameState, P> {
    String id();

    String version();

    InitializationSchema initializationSchema();

    S initialize(Map<String, InitializationValue> answers);

    ActionValidation validate(S state, ActionIntent intent);

    /** Converts an untrusted engine-owned narrative proposal into a candidate intent. */
    default ProposalIntentConversion convertProposal(String type, Map<String, String> fields, long seed) {
        return ProposalIntentConversion.reject("unsupported_engine_proposal");
    }

    EngineResolution<S> resolve(S state, ActionIntent intent);

    default ActionValidation validate(S state, ActionIntent intent, long worldDay) {
        return validate(state, intent);
    }

    default EngineResolution<S> resolve(S state, ActionIntent intent, long worldDay) {
        return resolve(state, intent);
    }

    /** Applies the selected engine's response to a confirmed world day transition. */
    default S afterWorldAdvance(S state, long worldDay, long seed, java.util.List<WorldEvent> newEvents) {
        return state;
    }

    P project(S state);
}
