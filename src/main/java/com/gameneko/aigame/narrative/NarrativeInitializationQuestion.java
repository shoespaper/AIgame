package com.gameneko.aigame.narrative;

import java.util.List;

/** A schema-backed question shown by the GM during game setup. */
public record NarrativeInitializationQuestion(String fieldId, String prompt, List<String> options) {
    public NarrativeInitializationQuestion {
        if (fieldId == null || fieldId.isBlank()) throw new IllegalArgumentException("Question field id is required");
        prompt = prompt == null ? "" : prompt;
        options = options == null ? List.of() : List.copyOf(options);
    }
}
