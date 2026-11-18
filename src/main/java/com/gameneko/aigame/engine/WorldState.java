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
        String originScene,
        List<TimedEvent> deadlineEvents,
        List<WorldEvent> worldEvents,
        Map<PriceModifier.Category, PriceModifier> activePriceModifiers,
        WorldEffects activeEffects
) implements GameState {
    public WorldState {
        flags = Map.copyOf(flags);
        threatHistory = List.copyOf(threatHistory);
        timedEvents = List.copyOf(timedEvents);
        incidentalEvents = List.copyOf(incidentalEvents);
        mainPlotStages = List.copyOf(mainPlotStages);
        priceModifiers = Map.copyOf(priceModifiers);
        deadlineEvents = deadlineEvents == null ? List.of() : List.copyOf(deadlineEvents);
        worldEvents = worldEvents == null ? List.of() : List.copyOf(worldEvents);
        activePriceModifiers = activePriceModifiers == null ? Map.of() : Map.copyOf(activePriceModifiers);
        activeEffects = activeEffects == null ? WorldEffects.none() : activeEffects;
    }

    public WorldState(String worldId, String contentVersion, long revision, String setting,
                      String settingDescription, String location, String currentRegion, String mainQuest,
                      long day, String time, String season, String weather, Map<String, Boolean> flags,
                      String threatLevel, long threatCounter, long threatInterval, List<String> threatHistory,
                      List<String> timedEvents, List<String> incidentalEvents, long lastTickDay,
                      boolean crisisTriggered, int mainPlotStage, List<String> mainPlotStages,
                      Map<String, Integer> priceModifiers, String originScene) {
        this(worldId, contentVersion, revision, setting, settingDescription, location, currentRegion, mainQuest,
                day, time, season, weather, flags, threatLevel, threatCounter, threatInterval, threatHistory,
                timedEvents, incidentalEvents, lastTickDay, crisisTriggered, mainPlotStage, mainPlotStages,
                priceModifiers, originScene, List.of(), List.of(), Map.of(), WorldEffects.none());
    }

    public WorldState schedule(TimedEvent event) {
        var next = new java.util.ArrayList<>(deadlineEvents);
        if (next.stream().anyMatch(value -> value.id().equals(event.id()))) throw new IllegalArgumentException("Timed event already exists");
        if (event.status() != TimedEvent.Status.ACTIVE || event.registeredDay() > day) throw new IllegalArgumentException("Timed event must be active and registered no later than today");
        next.add(event);
        return withTyped(next, worldEvents, activePriceModifiers, activeEffects);
    }

    public WorldState withRevision(long nextRevision) {
        return new WorldState(worldId, contentVersion, nextRevision, setting, settingDescription, location,
                currentRegion, mainQuest, day, time, season, weather, flags, threatLevel, threatCounter,
                threatInterval, threatHistory, timedEvents, incidentalEvents, lastTickDay, crisisTriggered,
                mainPlotStage, mainPlotStages, priceModifiers, originScene, deadlineEvents, worldEvents,
                activePriceModifiers, activeEffects);
    }

    public WorldState resolveTimedEvent(String eventId, String outcome) {
        if (deadlineEvents.stream().noneMatch(event -> event.id().equals(eventId) && event.status() == TimedEvent.Status.ACTIVE)) throw new IllegalArgumentException("Active timed event not found");
        var next = deadlineEvents.stream().map(event -> event.id().equals(eventId) ? event.resolve(day, outcome) : event).toList();
        return withTyped(next, worldEvents, activePriceModifiers, activeEffects);
    }

    public long price(long basePrice, PriceModifier.Category category) {
        if (basePrice < 0) throw new IllegalArgumentException("Base price must not be negative");
        java.math.BigInteger percent = java.math.BigInteger.valueOf(100);
        PriceModifier global = activePriceModifiers.get(PriceModifier.Category.ALL);
        if (global != null) percent = percent.multiply(java.math.BigInteger.valueOf(global.percent())).divide(java.math.BigInteger.valueOf(100));
        PriceModifier specific = activePriceModifiers.get(category);
        if (specific != null) percent = percent.multiply(java.math.BigInteger.valueOf(specific.percent())).divide(java.math.BigInteger.valueOf(100));
        return java.math.BigInteger.valueOf(basePrice).multiply(percent).divide(java.math.BigInteger.valueOf(100)).longValueExact();
    }

    private WorldState withTyped(List<TimedEvent> nextDeadlines, List<WorldEvent> nextEvents,
                                 Map<PriceModifier.Category, PriceModifier> nextPrices,
                                 WorldEffects effects) {
        return new WorldState(worldId, contentVersion, revision, setting, settingDescription, location, currentRegion,
                mainQuest, day, time, season, weather, flags, threatLevel, threatCounter, threatInterval,
                threatHistory, timedEvents, incidentalEvents, lastTickDay, crisisTriggered, mainPlotStage,
                mainPlotStages, priceModifiers, originScene, nextDeadlines, nextEvents, nextPrices, effects);
    }

}
