package com.gameneko.aigame.narrative;

/** Player-facing narration request. The snapshot is server-created and cannot be patched by a caller. */
public record NarrativeRequest(String gameId, long expectedRevision, String playerInput,
                               String locale, NarrativeSnapshot snapshot) {
    public NarrativeRequest {
        if (gameId == null || gameId.isBlank()) throw new IllegalArgumentException("Game id is required");
        playerInput = playerInput == null ? "" : playerInput.trim();
        if (playerInput.length() > 2000) throw new IllegalArgumentException("Player narrative input is too long");
        locale = locale == null || locale.isBlank() ? "zh-CN" : locale.trim();
        if (locale.length() > 32) throw new IllegalArgumentException("Narrative locale is invalid");
        if (snapshot == null) throw new IllegalArgumentException("Narrative snapshot is required");
    }
}
