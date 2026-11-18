package com.gameneko.aigame.api;

/** Client command envelope; the server maps the type to a closed AP action. */
public record GameActionRequest(long expectedRevision, String type, Long amount,
                                String itemName, String slot, String abilityId, String questId,
                                Long xp, Long copper, Long rations, Long potions,
                                String targetId, String battleAction, String region,
                                String encounterName, Integer enemyCount, Long seed,
                                String npcId, String npcName, String npcType, String npcLocation,
                                String interaction, String interactionResult,
                                Integer objectiveIndex, String failureReason,
                                String eventId, String eventDescription, String eventAnswer,
                                String eventConsequence, Long deadlineDays, String outcome,
                                String priceCategory, Integer pricePercent, Long expiresDay,
                                String itemCategory, String itemTier, String questTitle,
                                String questDescription, String questRegion, String objectiveId,
                                String objectiveDescription, Integer objectiveRequired) {
}
