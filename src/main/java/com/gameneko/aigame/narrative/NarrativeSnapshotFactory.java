package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.ConfirmedEvent;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.WorldEvent;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.engine.TimedEvent;

import java.util.ArrayList;

import java.util.List;

/** Builds the engine-neutral world and event context supplied to a narrator. */
public final class NarrativeSnapshotFactory {
    private NarrativeSnapshotFactory() {}

    public static NarrativeSnapshot from(GameInstance<?> instance, List<ConfirmedEvent> actionEvents,
                                         List<WorldEvent> confirmedWorldEvents, Object engineProjection) {
        return fromConfirmed(instance, actionEvents, confirmedWorldEvents, engineProjection);
    }

    public static NarrativeSnapshot fromConfirmed(GameInstance<?> instance, List<ConfirmedEvent> actionEvents,
                                                   List<WorldEvent> confirmedWorldEvents, Object engineProjection) {
        WorldState world = instance.worldState();
        NarrativeSnapshot.WorldView worldView = new NarrativeSnapshot.WorldView(world.day(), world.time(),
                world.location(), world.currentRegion(),
                world.deadlineEvents().stream().filter(e -> e.status() == TimedEvent.Status.ACTIVE).limit(12)
                        .map(e -> new NarrativeSnapshot.TimedEventView(e.id(), e.description(), e.daysRemaining(), e.status().name())).toList(),
                confirmedWorldEvents == null ? List.of() : confirmedWorldEvents.stream().skip(Math.max(0, confirmedWorldEvents.size() - 8L))
                        .map(e -> new NarrativeSnapshot.WorldEventView(e.day(), e.type().name(), e.description(), e.materialId())).toList(),
                world.activePriceModifiers().values().stream()
                        .map(modifier -> new NarrativeSnapshot.PriceView(modifier.category().name(), modifier.percent())).toList());
        List<NarrativeSnapshot.ConfirmedEvent> events = new ArrayList<>();
        if (actionEvents != null) actionEvents.stream().limit(20)
                .forEach(event -> events.add(new NarrativeSnapshot.ConfirmedEvent(event.source(), event.type(),
                        event.subject(), event.target(), event.amount(), event.description())));
        if (confirmedWorldEvents != null) confirmedWorldEvents.stream().skip(Math.max(0, confirmedWorldEvents.size() - 8L))
                .forEach(event -> events.add(new NarrativeSnapshot.ConfirmedEvent("world", event.type().name(), "world", "", event.day(), event.description())));
        return new NarrativeSnapshot(worldView, events, engineProjection);
    }
}
