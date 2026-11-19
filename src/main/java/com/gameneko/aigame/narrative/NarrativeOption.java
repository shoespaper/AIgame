package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.ActionIntent;

/** Player-facing GM suggestion paired with an untrusted structured intent candidate. */
public record NarrativeOption(String id, String label, ActionIntent intent) {
    public NarrativeOption {
        if (id == null || id.isBlank() || label == null || label.isBlank()) throw new IllegalArgumentException("Option id and label are required");
        id = id.trim();
    }
}
