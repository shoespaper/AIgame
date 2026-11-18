package com.gameneko.aigame.narrative;

import java.util.List;

/** Structured model output. Every field is bounded again by the proposal converter. */
public record NarrativeProposalCandidate(
        String type,
        Long amount,
        String itemName,
        String itemCategory,
        String itemTier,
        String questId,
        String questTitle,
        String questDescription,
        String questRegion,
        List<ObjectiveCandidate> objectives,
        Long rewardExperience,
        Long rewardCopper,
        Long rewardRations,
        Long rewardPotions,
        Long deadlineDays,
        String eventId,
        String eventDescription,
        String eventAnswer,
        String eventConsequence,
        String npcId,
        String interaction,
        String interactionResult
) {
    public NarrativeProposalCandidate {
        type = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    public record ObjectiveCandidate(String id, String description, Integer required) {
        public ObjectiveCandidate {
            required = required == null ? 1 : required;
        }
    }
}
