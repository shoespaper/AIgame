package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.InitializationValue;

/** Untrusted structured interpretation of free text; it requires schema validation. */
public record NarrativeParseCandidate(String fieldId, InitializationValue value, double confidence) {
    public NarrativeParseCandidate {
        if (fieldId == null || fieldId.isBlank() || value == null) throw new IllegalArgumentException("Parse candidate is incomplete");
        if (Double.isNaN(confidence) || confidence < 0 || confidence > 1) throw new IllegalArgumentException("Confidence must be between 0 and 1");
    }
}
