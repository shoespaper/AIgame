package com.gameneko.aigame.engine;

/** Metadata exposed to game creation before a concrete engine is selected. */
public record GameEngineDescriptor(String id, String version, String displayName,
                                   InitializationSchema initializationSchema) {
    public GameEngineDescriptor {
        if (id == null || id.isBlank() || version == null || version.isBlank()) throw new IllegalArgumentException("Engine identity is required");
        if (displayName == null || displayName.isBlank() || initializationSchema == null) throw new IllegalArgumentException("Engine descriptor is incomplete");
        id = id.trim();
        version = version.trim();
    }
}
