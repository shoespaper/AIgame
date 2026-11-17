package com.gameneko.aigame.content;

/** Read-only access to validated static content definitions. */
public interface ContentCatalog {
    WorldDefinition world(String worldId);
}
