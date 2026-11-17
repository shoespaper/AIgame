package com.gameneko.aigame.application;

/** Null override fields preserve Python's initial defaults; text is kept verbatim. */
public record InitializeWorldRequest(String worldId, String name, String description,
                                     String location, String region, String weather,
                                     String season, String time, String mainQuest, String originScene) {
    public static InitializeWorldRequest defaults(String worldId) {
        return new InitializeWorldRequest(worldId, null, null, null, null, null, null, null, null, null);
    }
}
