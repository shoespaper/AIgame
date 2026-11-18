package com.gameneko.aigame.engine;

/** Typed commands for world-owned state changes. */
public sealed interface WorldAction permits WorldAction.ScheduleEvent, WorldAction.ResolveEvent,
        WorldAction.SetPriceModifier, WorldAction.RemovePriceModifier {
    record ScheduleEvent(TimedEvent event) implements WorldAction {}
    record ResolveEvent(String eventId, String outcome) implements WorldAction {}
    record SetPriceModifier(PriceModifier modifier) implements WorldAction {}
    record RemovePriceModifier(PriceModifier.Category category) implements WorldAction {}
}
