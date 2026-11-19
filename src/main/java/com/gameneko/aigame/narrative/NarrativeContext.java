package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.NarrativeEffectPolicy;
import com.gameneko.aigame.engine.ProtocolVersion;

import java.util.List;

/** Engine-neutral, read-only context supplied to a GM narrative provider. */
public record NarrativeContext(ProtocolVersion protocolVersion, String worldId, String worldVersion,
                               String engineId, String engineVersion, NarrativeEffectPolicy effectPolicy,
                               Object engineProjection, List<com.gameneko.aigame.engine.ConfirmedEvent> confirmedEvents) {
    public NarrativeContext {
        protocolVersion = protocolVersion == null ? ProtocolVersion.current() : protocolVersion;
        if (worldId == null || worldId.isBlank() || worldVersion == null || worldVersion.isBlank()) {
            throw new IllegalArgumentException("World identity is required");
        }
        effectPolicy = effectPolicy == null ? NarrativeEffectPolicy.STORY_ONLY : effectPolicy;
        if (effectPolicy == NarrativeEffectPolicy.STORY_ONLY && engineProjection != null) {
            throw new IllegalArgumentException("Story-only context cannot contain an engine projection");
        }
        confirmedEvents = confirmedEvents == null ? List.of() : List.copyOf(confirmedEvents);
    }
}
