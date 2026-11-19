package com.gameneko.aigame.engine;

/** Server-confirmed, engine-neutral fact supplied to the narrator after resolution. */
public record ConfirmedEvent(String source, String type, String description) {
    public ConfirmedEvent {
        if (source == null || source.isBlank() || type == null || type.isBlank()) throw new IllegalArgumentException("Event source and type are required");
        source = source.trim();
        type = type.trim();
        description = description == null ? "" : description;
    }
}
