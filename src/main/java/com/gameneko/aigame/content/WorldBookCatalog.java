package com.gameneko.aigame.content;

/** Registry boundary for world books, kept separate from game engine registration. */
public interface WorldBookCatalog {
    WorldBook worldBook(String worldId);
}
