package com.gameneko.aigame.narrative;

import com.gameneko.aigame.application.port.NarrativeProposalConverter;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.TimedEvent;
import com.gameneko.aigame.engine.WorldAction;
import com.gameneko.aigame.engine.WorldEffects;
import com.gameneko.aigame.engine.GameEngineRuntime;
import org.springframework.stereotype.Service;


/** Generic proposal boundary. Ruleset-specific proposal validation belongs in an engine adapter. */
@Service
public final class ProposalConversionService implements NarrativeProposalConverter {
    public ProposalConversion convert(NarrativeProposalCandidate candidate, String gameId,
                                      long revision, int proposalIndex, WorldDefinition world) {
        return convert(candidate, gameId, revision, proposalIndex, 0, world);
    }

    @Override
    public ProposalConversion convert(NarrativeProposalCandidate candidate, String gameId, long revision,
                                      int proposalIndex, long currentDay, WorldDefinition world) {
        if (candidate == null || candidate.type().isBlank()) return reject(candidate, "proposal_type_missing");
        if (candidate.fields().size() > 32) return reject(candidate, "proposal_field_count_exceeded");
        if (candidate.fields().values().stream().anyMatch(value -> value == null || value.length() > 1000)) {
            return reject(candidate, "proposal_field_invalid");
        }
        if ("schedule_event".equals(candidate.type())) return scheduleEvent(candidate, currentDay);
        return reject(candidate, "engine_proposal_requires_selected_engine");
    }

    @Override
    public ProposalConversion convert(NarrativeProposalCandidate candidate, String gameId, long revision,
                                      int proposalIndex, long currentDay, WorldDefinition world,
                                      GameEngineRuntime<?, ?> engine) {
        if (candidate != null && "schedule_event".equals(candidate.type())) {
            return convert(candidate, gameId, revision, proposalIndex, currentDay, world);
        }
        if (engine == null) return reject(candidate, "engine_required");
        if (candidate == null || candidate.type().isBlank()) return reject(candidate, "proposal_type_missing");
        long seed = stableSeed(gameId, revision, proposalIndex);
        var result = engine.convertProposal(candidate.type(), candidate.fields(), seed);
        return result.accepted() ? new ProposalConversion(candidate, result.intent(), "") : reject(candidate, result.rejection());
    }

    private ProposalConversion scheduleEvent(NarrativeProposalCandidate candidate, long currentDay) {
        String id = field(candidate, "eventId");
        String description = field(candidate, "eventDescription");
        long duration;
        try { duration = Long.parseLong(field(candidate, "deadlineDays")); }
        catch (RuntimeException exception) { return reject(candidate, "event_deadline_invalid"); }
        if (id.isBlank() || id.length() > 80 || description.isBlank() || description.length() > 800
                || duration < 1 || duration > 60) return reject(candidate, "event_fields_invalid");
        var event = new TimedEvent(id, "narrative-proposal", description, field(candidate, "eventAnswer"),
                field(candidate, "eventConsequence"), currentDay, Math.addExact(currentDay, duration), duration,
                TimedEvent.Status.ACTIVE, WorldEffects.none());
        return new ProposalConversion(candidate, null, new WorldAction.ScheduleEvent(event), "");
    }

    private static String field(NarrativeProposalCandidate candidate, String name) {
        return candidate.fields().getOrDefault(name, "");
    }

    private static ProposalConversion reject(NarrativeProposalCandidate candidate, String reason) {
        return new ProposalConversion(candidate, null, reason);
    }

    private static long stableSeed(String gameId, long revision, int index) {
        long seed = 0xcbf29ce484222325L;
        if (gameId != null) for (int i = 0; i < gameId.length(); i++) seed = (seed ^ gameId.charAt(i)) * 0x100000001b3L;
        return seed ^ Long.rotateLeft(revision, 17) ^ index;
    }
}
