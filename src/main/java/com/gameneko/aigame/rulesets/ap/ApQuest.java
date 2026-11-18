package com.gameneko.aigame.rulesets.ap;

import java.util.List;

/** AP quest record with explicit lifecycle, objectives, deadline, and one-time reward settlement. */
public record ApQuest(String questId, String title, String description, String region, Status status,
                      List<Objective> objectives, Reward reward, long generatedDay,
                      long deadlineDays, long daysRemaining, String failureReason) {
    public enum Status { AVAILABLE, ACTIVE, COMPLETED, FAILED }

    public ApQuest {
        if (questId == null || questId.isBlank()) throw new IllegalArgumentException("Quest id is required");
        title = title == null ? questId : title;
        description = description == null ? "" : description;
        region = region == null ? "" : region;
        status = status == null ? Status.AVAILABLE : status;
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
        reward = reward == null ? Reward.none() : reward;
        if (deadlineDays < 0 || daysRemaining < 0) throw new IllegalArgumentException("Quest deadline must not be negative");
        failureReason = failureReason == null ? "" : failureReason;
    }

    public ApQuest accept() {
        if (status != Status.AVAILABLE) throw new IllegalArgumentException("Quest is not available");
        return new ApQuest(questId, title, description, region, Status.ACTIVE, objectives, reward,
                generatedDay, deadlineDays, deadlineDays, "");
    }

    public ApQuest progress(int objectiveIndex, int amount) {
        if (status != Status.ACTIVE) throw new IllegalArgumentException("Quest is not active");
        if (objectiveIndex < 0 || objectiveIndex >= objectives.size()) throw new IllegalArgumentException("Unknown quest objective");
        if (amount <= 0) throw new IllegalArgumentException("Objective progress must be positive");
        var next = new java.util.ArrayList<>(objectives);
        next.set(objectiveIndex, next.get(objectiveIndex).advance(amount));
        return copy(status, next, daysRemaining, failureReason);
    }

    public ApQuest tickDay() {
        if (status != Status.ACTIVE || deadlineDays == 0) return this;
        long remaining = Math.max(0, daysRemaining - 1);
        return copy(remaining == 0 ? Status.FAILED : status, objectives, remaining,
                remaining == 0 ? "deadline_expired" : failureReason);
    }

    public ApQuest complete() {
        if (status != Status.ACTIVE) throw new IllegalArgumentException("Quest is not active");
        if (objectives.stream().anyMatch(objective -> !objective.complete())) {
            throw new IllegalArgumentException("Quest objectives are incomplete");
        }
        return copy(Status.COMPLETED, objectives, daysRemaining, "");
    }

    public ApQuest fail(String reason) {
        if (status != Status.ACTIVE && status != Status.AVAILABLE) throw new IllegalArgumentException("Quest cannot be failed");
        return copy(Status.FAILED, objectives, daysRemaining, reason);
    }

    private ApQuest copy(Status nextStatus, List<Objective> nextObjectives, long remaining, String reason) {
        return new ApQuest(questId, title, description, region, nextStatus, nextObjectives,
                reward, generatedDay, deadlineDays, remaining, reason);
    }

    public record Objective(String id, String description, int progress, int required) {
        public Objective {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Objective id is required");
            description = description == null ? "" : description;
            if (progress < 0 || required <= 0) throw new IllegalArgumentException("Invalid objective progress");
            progress = Math.min(progress, required);
        }

        public boolean complete() { return progress >= required; }
        public Objective advance(int amount) { return new Objective(id, description, Math.addExact(progress, amount), required); }
    }

    public record Reward(long experience, long copper, long rations, long potions) {
        public Reward {
            if (experience < 0 || copper < 0 || rations < 0 || potions < 0) {
                throw new IllegalArgumentException("Quest rewards must not be negative");
            }
        }
        public static Reward none() { return new Reward(0, 0, 0, 0); }
    }
}
