package com.gameneko.aigame.engine;

import java.util.List;
import java.util.Map;

/** Authoritative dynamic world facts for one in-memory game initialization. */
public record WorldState(
        String worldId,
        String contentVersion,
        long revision,
        String setting,
        String settingDescription,
        String location,
        String currentRegion,
        String mainQuest,
        long day,
        String time,
        String season,
        String weather,
        Map<String, Boolean> flags,
        String threatLevel,
        long threatCounter,
        long threatInterval,
        List<String> threatHistory,
        List<String> timedEvents,
        List<String> incidentalEvents,
        long lastTickDay,
        boolean crisisTriggered,
        int mainPlotStage,
        List<String> mainPlotStages,
        Map<String, Integer> priceModifiers,
        String originScene
) implements GameState {
    public WorldState {
        flags = Map.copyOf(flags);
        threatHistory = List.copyOf(threatHistory);
        timedEvents = List.copyOf(timedEvents);
        incidentalEvents = List.copyOf(incidentalEvents);
        mainPlotStages = List.copyOf(mainPlotStages);
        priceModifiers = Map.copyOf(priceModifiers);
    }

}
