package com.gameneko.aigame.content;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only projection of the static world book used by initialization and AP validation. */
public record WorldDefinition(String worldId, String contentVersion, String nameHint,
                              String descriptionHint, long defaultThreatInterval,
                              Map<String, List<String>> regionMonsterTypes,
                               Map<String, DifficultyDefinition> difficulties,
                               Map<String, TalentDefinition> talents,
                               Map<String, AbilityDefinition> abilities) {
    public WorldDefinition {
        regionMonsterTypes = regionMonsterTypes.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        difficulties = Map.copyOf(difficulties);
        talents = Map.copyOf(talents);
        abilities = Map.copyOf(abilities);
    }

    public WorldDefinition(String worldId, String contentVersion, String nameHint,
                           String descriptionHint, long defaultThreatInterval,
                           Map<String, List<String>> regionMonsterTypes,
                           Map<String, DifficultyDefinition> difficulties,
                           Map<String, TalentDefinition> talents) {
        this(worldId, contentVersion, nameHint, descriptionHint, defaultThreatInterval,
                regionMonsterTypes, difficulties, talents, Map.of());
    }

    public record DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                       long startingPotions, int hpRecoveryBonus, String label, String description) {
        public DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                    long startingPotions, String label, String description) {
            this(threatInterval, startingCopper, startingRations, startingPotions, 0, label, description);
        }
    }

    public record TalentDefinition(String name, String description) {}

    public record AbilityDefinition(String id, String kind, int tier, int apCost, int spCost,
                                     int power, Map<String, Integer> prerequisites,
                                     String name, String effectHint, String description) {
        public AbilityDefinition {
            prerequisites = Map.copyOf(prerequisites);
        }
    }
}
