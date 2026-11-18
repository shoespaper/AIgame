package com.gameneko.aigame.narrative;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.TimedEvent;
import com.gameneko.aigame.engine.WorldAction;
import com.gameneko.aigame.rulesets.ap.ApQuest;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Whitelists model proposals and converts them to AP actions without changing state. */
@Service
public final class ProposalConversionService {
    private static final long MAX_EXPERIENCE = 250;
    private static final long MAX_QUEST_EXPERIENCE = 500;
    private static final long MAX_QUEST_COPPER = 500;

    public ProposalConversion convert(NarrativeProposalCandidate candidate, String gameId,
                                       long revision, int proposalIndex, WorldDefinition world) {
        return convert(candidate, gameId, revision, proposalIndex, 0, world);
    }

    public ProposalConversion convert(NarrativeProposalCandidate candidate, String gameId,
                                       long revision, int proposalIndex, long currentDay,
                                       WorldDefinition world) {
        if (candidate == null || gameId == null || world == null) return reject(candidate, "proposal_context_missing");
        return switch (candidate.type()) {
            case "gain_experience" -> gainExperience(candidate);
            case "acquire_item" -> acquireItem(candidate, gameId, revision, proposalIndex, world);
            case "offer_quest" -> offerQuest(candidate, gameId, revision, proposalIndex, currentDay, world);
            case "schedule_event" -> scheduleEvent(candidate, currentDay);
            default -> reject(candidate, "proposal_type_not_allowed");
        };
    }

    private ProposalConversion gainExperience(NarrativeProposalCandidate candidate) {
        long amount = value(candidate.amount());
        if (amount <= 0 || amount > MAX_EXPERIENCE) return reject(candidate, "experience_out_of_range");
        return accept(candidate, new ApRulesetRuntime.GrantExperience(amount));
    }

    private ProposalConversion acquireItem(NarrativeProposalCandidate candidate, String gameId,
                                           long revision, int index, WorldDefinition world) {
        if (!text(candidate.itemName(), 1, 80) || !text(candidate.itemCategory(), 1, 40)
                || !text(candidate.itemTier(), 1, 40)) return reject(candidate, "item_fields_invalid");
        if (!world.itemTemplates().containsKey(candidate.itemCategory() + ":" + candidate.itemTier())) {
            return reject(candidate, "item_template_unknown");
        }
        long seed = stableSeed(gameId, revision, index);
        return accept(candidate, new ApRulesetRuntime.AcquireItem(candidate.itemName(), candidate.itemCategory(), candidate.itemTier(), seed));
    }

    private ProposalConversion offerQuest(NarrativeProposalCandidate candidate, String gameId,
                                          long revision, int index, long currentDay, WorldDefinition world) {
        if (!text(candidate.questTitle(), 1, 120) || !text(candidate.questDescription(), 1, 800)) return reject(candidate, "quest_text_invalid");
        if (candidate.questRegion() != null && !candidate.questRegion().isBlank()
                && !world.regionMonsterTypes().containsKey(candidate.questRegion())) return reject(candidate, "quest_region_unknown");
        List<NarrativeProposalCandidate.ObjectiveCandidate> proposedObjectives = candidate.objectives();
        if (proposedObjectives.isEmpty() || proposedObjectives.size() > 4) return reject(candidate, "quest_objective_count_invalid");
        List<ApQuest.Objective> objectives = new ArrayList<>();
        for (int i = 0; i < proposedObjectives.size(); i++) {
            var objective = proposedObjectives.get(i);
            int required = objective.required() == null ? 1 : objective.required();
            if (!text(objective.id(), 1, 80) || objective.description() == null || objective.description().length() > 300
                    || required < 1 || required > 20) return reject(candidate, "quest_objective_invalid");
            objectives.add(new ApQuest.Objective(objective.id(), objective.description(), 0, required));
        }
        long xp = value(candidate.rewardExperience());
        long copper = value(candidate.rewardCopper());
        long rations = value(candidate.rewardRations());
        long potions = value(candidate.rewardPotions());
        long deadline = value(candidate.deadlineDays());
        if (xp < 0 || xp > MAX_QUEST_EXPERIENCE || copper < 0 || copper > MAX_QUEST_COPPER
                || rations < 0 || rations > 10 || potions < 0 || potions > 5
                || deadline < 0 || deadline > 30) return reject(candidate, "quest_reward_or_deadline_out_of_range");
        String questId = "ai-quest-" + revision + "-" + index;
        var quest = new ApQuest(questId, candidate.questTitle(), candidate.questDescription(),
                candidate.questRegion(), ApQuest.Status.AVAILABLE, objectives,
                new ApQuest.Reward(xp, copper, rations, potions), currentDay, deadline, deadline, "");
        return accept(candidate, new ApRulesetRuntime.OfferQuest(quest));
    }

    private ProposalConversion scheduleEvent(NarrativeProposalCandidate candidate, long currentDay) {
        long duration = value(candidate.deadlineDays());
        if (!text(candidate.eventId(), 1, 80) || !text(candidate.eventDescription(), 1, 800)
                || duration < 1 || duration > 60
                || candidate.eventAnswer() != null && candidate.eventAnswer().length() > 800
                || candidate.eventConsequence() != null && candidate.eventConsequence().length() > 800) return reject(candidate, "event_fields_invalid");
        var event = new TimedEvent(candidate.eventId(), "ai-proposal", candidate.eventDescription(),
                candidate.eventAnswer(), candidate.eventConsequence(), currentDay, Math.addExact(currentDay, duration),
                duration, TimedEvent.Status.ACTIVE, com.gameneko.aigame.engine.WorldEffects.none());
        return acceptWorld(candidate, new WorldAction.ScheduleEvent(event));
    }

    private long stableSeed(String gameId, long revision, int index) {
        long seed = 0xcbf29ce484222325L;
        for (int i = 0; i < gameId.length(); i++) seed = (seed ^ gameId.charAt(i)) * 0x100000001b3L;
        return seed ^ Long.rotateLeft(revision, 17) ^ index;
    }

    private static long value(Long value) { return value == null ? 0 : value; }
    private static boolean text(String value, int min, int max) { return value != null && value.trim().length() >= min && value.length() <= max; }
    private static ProposalConversion accept(NarrativeProposalCandidate candidate, ApRulesetRuntime.ApAction action) { return new ProposalConversion(candidate, action, ""); }
    private static ProposalConversion acceptWorld(NarrativeProposalCandidate candidate, WorldAction action) { return new ProposalConversion(candidate, null, action, ""); }
    private static ProposalConversion reject(NarrativeProposalCandidate candidate, String reason) { return new ProposalConversion(candidate, null, reason); }
}
