package com.gameneko.aigame.api;

import com.gameneko.aigame.engine.WorldAction;
import com.gameneko.aigame.engine.ActionIntent;

/** A validated request shape mapped to exactly one ruleset or world command. */
public record MappedGameAction(ActionIntent intent, WorldAction worldAction) {
    public MappedGameAction {
        if ((intent == null) == (worldAction == null)) {
            throw new IllegalArgumentException("Exactly one mapped action is required");
        }
    }

    public static MappedGameAction intent(ActionIntent action) { return new MappedGameAction(action, null); }
    public static MappedGameAction world(WorldAction action) { return new MappedGameAction(null, action); }
}
