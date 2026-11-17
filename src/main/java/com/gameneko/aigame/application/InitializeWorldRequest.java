package com.gameneko.aigame.application;

/** Null override fields preserve Python's initial defaults; text is kept verbatim. */
public record InitializeWorldRequest(String worldId, String name, String description,
                                     String location, String region, String weather,
                                     String season, String time, String mainQuest, String originScene,
                                     String characterName, Integer age, String gender, String archetype,
                                     String difficulty) {

    public InitializeWorldRequest(String worldId, String name, String description, String location, String region,
                                  String weather, String season, String time, String mainQuest, String originScene) {
        this(worldId, name, description, location, region, weather, season, time, mainQuest, originScene,
                "旅人", 16, "未定", "swordsman", "adventure");
    }

    public static InitializeWorldRequest defaults(String worldId) {
        return new InitializeWorldRequest(worldId, null, null, null, null, null, null, null, null, null,
                "旅人", 16, "未定", "swordsman", "adventure");
    }
}
