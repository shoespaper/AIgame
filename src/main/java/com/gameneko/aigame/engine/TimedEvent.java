package com.gameneko.aigame.engine;

/** A world event with a durable deadline and explicit resolution state. */
public record TimedEvent(String id, String kind, String description, String answer,
                         String consequence, long registeredDay, long deadlineDay,
                         long daysRemaining, Status status, WorldEffects effects,
                         String outcome, long resolvedDay) {
    public enum Status { ACTIVE, RESOLVED, EXPIRED }

    public TimedEvent {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Timed event id is required");
        kind = kind == null ? "foreshadow" : kind;
        description = description == null ? "" : description;
        answer = answer == null ? "" : answer;
        consequence = consequence == null ? "" : consequence;
        if (deadlineDay < registeredDay) throw new IllegalArgumentException("Timed event deadline is invalid");
        status = status == null ? Status.ACTIVE : status;
        effects = effects == null ? WorldEffects.none() : effects;
        outcome = outcome == null ? "" : outcome;
        if (resolvedDay < 0) throw new IllegalArgumentException("Resolved day must not be negative");
    }

    public TimedEvent(String id, String kind, String description, String answer, String consequence,
                      long registeredDay, long deadlineDay, long daysRemaining, Status status,
                      WorldEffects effects) {
        this(id, kind, description, answer, consequence, registeredDay, deadlineDay, daysRemaining,
                status, effects, "", 0);
    }

    public TimedEvent tick(long day) {
        if (status != Status.ACTIVE) return this;
        long remaining = Math.max(0, deadlineDay - day);
        return remaining == 0
                ? new TimedEvent(id, kind, description, answer, consequence, registeredDay,
                deadlineDay, 0, Status.EXPIRED, effects, outcome, day)
                : new TimedEvent(id, kind, description, answer, consequence, registeredDay,
                deadlineDay, remaining, status, effects, outcome, resolvedDay);
    }

    public TimedEvent resolve(long day, String outcome) {
        if (status != Status.ACTIVE) throw new IllegalArgumentException("Timed event is not active");
        return new TimedEvent(id, kind, description, answer, consequence,
                registeredDay, deadlineDay, Math.max(0, deadlineDay - day), Status.RESOLVED,
                effects, outcome, day);
    }
}
