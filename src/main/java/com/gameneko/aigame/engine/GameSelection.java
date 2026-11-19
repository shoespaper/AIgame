package com.gameneko.aigame.engine;

/** User-selected content and optional rules engine for a new game. */
public record GameSelection(String worldId, String engineId) {
    public GameSelection {
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("World id is required");
        worldId = worldId.trim();
        engineId = engineId == null || engineId.isBlank() ? null : engineId.trim();
    }

    public boolean hasEngine() { return engineId != null; }
}
