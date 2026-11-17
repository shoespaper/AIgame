package com.gameneko.aigame.content;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Initialization's read-only projection of the static world book, not AP rules content. */
public record WorldDefinition(String worldId, String contentVersion, String nameHint,
                              String descriptionHint, long defaultThreatInterval,
                              Map<String, List<String>> regionMonsterTypes) {
    public WorldDefinition {
        regionMonsterTypes = regionMonsterTypes.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
