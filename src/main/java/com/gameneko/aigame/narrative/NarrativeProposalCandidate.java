package com.gameneko.aigame.narrative;

import java.util.Map;

/** Untrusted structured proposal; only the selected world or engine may interpret its fields. */
public record NarrativeProposalCandidate(String type, Map<String, String> fields) {
    public NarrativeProposalCandidate {
        type = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
