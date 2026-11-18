package com.gameneko.aigame.api;

import com.gameneko.aigame.engine.WorldAction;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.ApAction;

/** A validated request shape mapped to exactly one ruleset or world command. */
public record MappedGameAction(ApAction apAction, WorldAction worldAction) {
    public MappedGameAction {
        if ((apAction == null) == (worldAction == null)) {
            throw new IllegalArgumentException("Exactly one mapped action is required");
        }
    }

    public static MappedGameAction ap(ApAction action) { return new MappedGameAction(action, null); }
    public static MappedGameAction world(WorldAction action) { return new MappedGameAction(null, action); }
}
