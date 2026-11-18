package com.gameneko.aigame.api;

import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.engine.PriceModifier;
import com.gameneko.aigame.engine.TimedEvent;
import com.gameneko.aigame.engine.WorldEvent;
import com.gameneko.aigame.engine.WorldEffects;

import java.util.List;
import java.util.Map;

/** Stable read-only API projection of an initialized world. */
public record WorldInitializationResponse(
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
        String originScene,
        List<TimedEvent> deadlineEvents,
        List<WorldEvent> worldEvents,
        Map<PriceModifier.Category, PriceModifier> activePriceModifiers,
        WorldEffects activeEffects
) {
    public static WorldInitializationResponse from(WorldState state) {
        return new WorldInitializationResponse(
                state.worldId(), state.contentVersion(), state.revision(),
                state.setting(), state.settingDescription(), state.location(), state.currentRegion(),
                state.mainQuest(), state.day(), state.time(), state.season(), state.weather(),
                state.flags(), state.threatLevel(), state.threatCounter(), state.threatInterval(),
                state.threatHistory(), state.timedEvents(), state.incidentalEvents(), state.lastTickDay(),
                state.crisisTriggered(), state.mainPlotStage(), state.mainPlotStages(),
                state.priceModifiers(), state.originScene(), state.deadlineEvents(), state.worldEvents(),
                state.activePriceModifiers(), state.activeEffects());
    }
}
