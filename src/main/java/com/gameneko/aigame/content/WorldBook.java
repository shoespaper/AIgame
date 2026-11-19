package com.gameneko.aigame.content;

import com.gameneko.aigame.engine.InitializationSchema;

/** A world book: static world content plus the fields it wants answered at creation time. */
public record WorldBook(WorldDefinition definition, InitializationSchema initializationSchema) {
    public WorldBook {
        if (definition == null || initializationSchema == null) throw new IllegalArgumentException("World book content and schema are required");
    }

    public String id() { return definition.worldId(); }
    public String version() { return definition.contentVersion(); }
}
