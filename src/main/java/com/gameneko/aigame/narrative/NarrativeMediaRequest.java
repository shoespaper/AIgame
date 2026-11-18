package com.gameneko.aigame.narrative;

/** A constrained request for a later media provider. It is never authoritative game state. */
public record NarrativeMediaRequest(String kind, String prompt, String assetId) {
    public NarrativeMediaRequest {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Media kind is required");
        prompt = prompt == null ? "" : prompt;
        assetId = assetId == null ? "" : assetId;
    }
}
