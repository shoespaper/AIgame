package com.gameneko.aigame.content;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Initialization's read-only projection of the static world book, not AP rules content. */
public record WorldDefinition(String worldId, String contentVersion, String nameHint,
                              String descriptionHint, long defaultThreatInterval,
                              Map<String, List<String>> regionMonsterTypes,
                              Map<String, DifficultyDefinition> difficulties,
                              Map<String, TalentDefinition> talents) {
    public WorldDefinition {
        regionMonsterTypes = regionMonsterTypes.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        difficulties = Map.copyOf(difficulties);
        talents = Map.copyOf(talents);
    }

    public record DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                       long startingPotions, int hpRecoveryBonus, String label, String description) {
        public DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                    long startingPotions, String label, String description) {
            this(threatInterval, startingCopper, startingRations, startingPotions, 0, label, description);
        }
    }

    public record TalentDefinition(String name, String description) {}
}
