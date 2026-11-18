package com.gameneko.aigame.rulesets.ap;

import java.util.List;

/** Persistent NPC identity, schedule, relationship, mood, and interaction history. */
public record ApNpc(String npcId, String name, String type, String personality, String location,
                    int relationship, int mood, Status status, List<ScheduleEntry> schedule,
                    List<LifeEvent> lifeEvents, List<Interaction> interactions,
                    boolean canTrain, boolean canCombat, boolean sellsItems, boolean givesQuests,
                    String scheduleTemplate) {
    public enum Status { ACTIVE, DEAD, MISSING }
    public enum InteractionResult { POSITIVE, NEUTRAL, NEGATIVE }

    public ApNpc {
        if (npcId == null || npcId.isBlank()) throw new IllegalArgumentException("NPC id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("NPC name is required");
        type = type == null ? "other" : type;
        personality = personality == null ? "" : personality;
        location = location == null ? "" : location;
        scheduleTemplate = scheduleTemplate == null ? "" : scheduleTemplate;
        relationship = Math.max(-100, Math.min(100, relationship));
        mood = Math.max(-10, Math.min(10, mood));
        status = status == null ? Status.ACTIVE : status;
        schedule = schedule == null ? List.of() : List.copyOf(schedule);
        lifeEvents = lifeEvents == null ? List.of() : List.copyOf(lifeEvents);
        interactions = interactions == null ? List.of() : List.copyOf(interactions);
    }

    public ApNpc interact(long day, String action, InteractionResult result, int relationshipDelta, int moodDelta) {
        if (status != Status.ACTIVE) throw new IllegalArgumentException("NPC is not available for interaction");
        var history = new java.util.ArrayList<>(interactions);
        history.add(new Interaction(day, action, result, relationshipDelta, moodDelta));
        return copy(Math.max(-100, Math.min(100, relationship + relationshipDelta)),
                Math.max(-10, Math.min(10, mood + moodDelta)), lifeEvents, history);
    }

    public ApNpc dailyTick(long day, long seed) {
        if (status != Status.ACTIVE) return this;
        var random = new java.util.SplittableRandom(seed);
        int nextRelationship = relationship > 0 ? Math.max(0, relationship - random.nextInt(0, 2))
                : relationship < 0 ? Math.min(0, relationship + random.nextInt(0, 2)) : 0;
        int nextMood = mood > 0 ? mood - 1 : mood < 0 ? mood + 1 : 0;
        return copy(nextRelationship, nextMood, lifeEvents, interactions);
    }

    public ApNpc withLifeEvent(LifeEvent event) {
        var next = new java.util.ArrayList<>(lifeEvents);
        next.add(event);
        return new ApNpc(npcId, name, type, personality, location, relationship, mood, status,
                schedule, next, interactions, canTrain, canCombat, sellsItems, givesQuests, scheduleTemplate);
    }

    public ApNpc withStatus(Status nextStatus) {
        return new ApNpc(npcId, name, type, personality, location, relationship, mood, nextStatus,
                schedule, lifeEvents, interactions, canTrain, canCombat, sellsItems, givesQuests, scheduleTemplate);
    }

    public ApNpc shiftMood(int amount) {
        return new ApNpc(npcId, name, type, personality, location, relationship,
                Math.max(-10, Math.min(10, mood + amount)), status, schedule, lifeEvents,
                interactions, canTrain, canCombat, sellsItems, givesQuests, scheduleTemplate);
    }

    private ApNpc copy(int nextRelationship, int nextMood, List<LifeEvent> nextLifeEvents,
                       List<Interaction> nextInteractions) {
        return new ApNpc(npcId, name, type, personality, location, nextRelationship, nextMood,
                status, schedule, nextLifeEvents, nextInteractions, canTrain, canCombat, sellsItems, givesQuests, scheduleTemplate);
    }

    public record ScheduleEntry(String time, String location, String activity) {
        public ScheduleEntry {
            time = time == null ? "" : time;
            location = location == null ? "" : location;
            activity = activity == null ? "" : activity;
        }
    }

    public record LifeEvent(long day, String description) {
        public LifeEvent { description = description == null ? "" : description; }
    }

    public record Interaction(long day, String action, InteractionResult result,
                              int relationshipDelta, int moodDelta) {
        public Interaction {
            if (day < 0) throw new IllegalArgumentException("Interaction day must not be negative");
            if (action == null || action.isBlank()) throw new IllegalArgumentException("Interaction action is required");
            result = result == null ? InteractionResult.NEUTRAL : result;
        }
    }
}
