package com.gameneko.aigame.api;

/** HTTP DTO for the in-memory world initialization endpoint. */
public record InitializeWorldHttpRequest(
        String worldId,
        String name,
        String description,
        String location,
        String region,
        String weather,
        String season,
        String time,
        String mainQuest,
        String originScene,
        String characterName,
        Integer age,
        String gender,
        String archetype,
        String difficulty
) {
}
