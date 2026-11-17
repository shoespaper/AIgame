package com.gameneko.aigame.api;

import com.gameneko.aigame.engine.WorldState;

import java.util.List;
import java.util.Map;

/** API payload for persisting the current world projection. */
public record SaveWorldRequest(
        String worldId, String contentVersion, long revision,
        String setting, String settingDescription, String location, String currentRegion, String mainQuest,
        long day, String time, String season, String weather, Map<String, Boolean> flags,
        String threatLevel, long threatCounter, long threatInterval, List<String> threatHistory,
        List<String> timedEvents, List<String> incidentalEvents, long lastTickDay, boolean crisisTriggered,
        int mainPlotStage, List<String> mainPlotStages, Map<String, Integer> priceModifiers, String originScene
) {
    public WorldState toState() {
        requireText(worldId, "worldId");
        requireText(contentVersion, "contentVersion");
        requireText(time, "time");
        requireText(threatLevel, "threatLevel");
        if (threatInterval <= 0) {
            throw new IllegalArgumentException("threatInterval must be positive");
        }
        return new WorldState(worldId, contentVersion, revision, setting, settingDescription, location,
                currentRegion, mainQuest, day, time, season, weather,
                flags == null ? Map.of() : flags, threatLevel, threatCounter, threatInterval,
                threatHistory == null ? List.of() : threatHistory, timedEvents == null ? List.of() : timedEvents,
                incidentalEvents == null ? List.of() : incidentalEvents, lastTickDay, crisisTriggered,
                mainPlotStage, mainPlotStages == null ? List.of() : mainPlotStages,
                priceModifiers == null ? Map.of() : priceModifiers, originScene);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
