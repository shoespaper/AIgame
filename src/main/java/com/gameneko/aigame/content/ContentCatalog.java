package com.gameneko.aigame.content;

/** Read-only access to validated static content definitions. */
public interface ContentCatalog extends WorldBookCatalog, TemplateCatalog {
    WorldDefinition world(String worldId);

    @Override
    default WorldBook worldBook(String worldId) {
        WorldDefinition definition = world(worldId);
        return new WorldBook(definition);
    }
}
