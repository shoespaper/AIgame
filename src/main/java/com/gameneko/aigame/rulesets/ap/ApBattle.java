package com.gameneko.aigame.rulesets.ap;

import java.util.List;
import java.util.Set;

/** Durable combat snapshot. Every action resolves against this snapshot and returns a replacement. */
public final class ApBattle {
    private ApBattle() {}

    public enum Result { ACTIVE, VICTORY, DEFEAT, ESCAPED }
    public enum ActionKind { ATTACK, HEAVY, SPELL, SKILL, DEFEND, DODGE, ITEM, FOCUS, WAIT }

    public record Snapshot(int round, int turnIndex, List<String> turnOrder,
                           List<Combatant> combatants, List<BattleEvent> log,
                           boolean active, Result result, String encounterName,
                           long seed, boolean rewardsSettled) {
        public Snapshot {
            if (round < 1 || turnIndex < 0) throw new IllegalArgumentException("Invalid combat turn");
            turnOrder = turnOrder == null ? List.of() : List.copyOf(turnOrder);
            combatants = combatants == null ? List.of() : List.copyOf(combatants);
            log = log == null ? List.of() : List.copyOf(log);
            result = result == null ? Result.ACTIVE : result;
            encounterName = encounterName == null ? "Encounter" : encounterName;
            if (active && turnIndex >= turnOrder.size()) throw new IllegalArgumentException("Combat turn index is outside initiative order");
        }

        public Combatant combatant(String id) {
            return combatants.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown combatant: " + id));
        }

        public Combatant currentActor() { return combatant(turnOrder.get(turnIndex)); }

        public Snapshot with(int nextRound, int nextTurnIndex, List<Combatant> nextCombatants,
                             List<BattleEvent> nextLog, boolean nextActive, Result nextResult,
                             boolean settled) {
            return new Snapshot(nextRound, nextTurnIndex, turnOrder, nextCombatants, nextLog,
                    nextActive, nextResult, encounterName, seed, settled);
        }
    }

    public record Combatant(String id, String name, boolean player, String monsterType,
                            int hp, int maxHp, int sp, int maxSp, int power, int mitigation,
                            int speed, int maxAp, int currentAp, int guard, int exposedRounds,
                            boolean defending, boolean dodging, List<CombatStatus> statuses,
                            long experienceReward, long copperReward, List<String> lootItems,
                            Set<String> usedAbilities) {
        public Combatant {
            if (id == null || id.isBlank() || name == null || name.isBlank()) throw new IllegalArgumentException("Combatant identity is required");
            if (maxHp <= 0 || hp < 0 || hp > maxHp || sp < 0 || sp > maxSp || maxAp < 0
                    || currentAp < 0 || currentAp > maxAp || mitigation < 0 || guard < 0 || exposedRounds < 0) {
                throw new IllegalArgumentException("Invalid combatant values");
            }
            monsterType = monsterType == null ? "" : monsterType;
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            lootItems = lootItems == null ? List.of() : List.copyOf(lootItems);
            usedAbilities = usedAbilities == null ? Set.of() : Set.copyOf(usedAbilities);
        }

        public boolean alive() { return hp > 0; }

        public Combatant update(int nextHp, int nextSp, int nextPower, int nextMitigation,
                                int nextAp, int nextGuard, int nextExposed, boolean nextDefending,
                                boolean nextDodging, List<CombatStatus> nextStatuses, Set<String> nextUsed) {
            return new Combatant(id, name, player, monsterType, Math.max(0, Math.min(maxHp, nextHp)), maxHp,
                    Math.max(0, Math.min(maxSp, nextSp)), maxSp, Math.max(1, nextPower),
                    Math.max(0, nextMitigation), speed, maxAp, Math.max(0, Math.min(maxAp, nextAp)),
                    Math.max(0, nextGuard), Math.max(0, nextExposed), nextDefending, nextDodging,
                    nextStatuses, experienceReward, copperReward, lootItems, nextUsed);
        }
    }

    public record CombatStatus(String name, String effect, int amount, int duration) {
        public CombatStatus {
            name = name == null ? "effect" : name;
            effect = effect == null ? "" : effect;
            if (duration == 0) throw new IllegalArgumentException("Combat status duration must not be zero");
        }
    }

    public record BattleEvent(int round, String actorId, ActionKind action, String targetId,
                              int apCost, int spCost, int power, int damage, int healing,
                              String outcome, String effect) {
        public BattleEvent {
            if (round < 1 || apCost < 0 || spCost < 0 || damage < 0 || healing < 0) {
                throw new IllegalArgumentException("Invalid battle event values");
            }
            outcome = outcome == null ? "" : outcome;
            effect = effect == null ? "" : effect;
        }
    }
}
