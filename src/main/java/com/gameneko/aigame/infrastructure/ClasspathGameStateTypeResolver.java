package com.gameneko.aigame.infrastructure;

import com.gameneko.aigame.engine.GameStateTypeResolver;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.springframework.stereotype.Component;

/** Persistence registry for typed engine states available on this classpath. */
@Component
public final class ClasspathGameStateTypeResolver implements GameStateTypeResolver {
    @Override
    public Class<?> stateType(String engineId) {
        if ("ap".equalsIgnoreCase(engineId)) return ApRulesetRuntime.ApState.class;
        throw new IllegalArgumentException("Unsupported engine state: " + engineId);
    }
}
