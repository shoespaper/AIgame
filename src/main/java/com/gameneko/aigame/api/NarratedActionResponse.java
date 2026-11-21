package com.gameneko.aigame.api;

import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.narrative.NarrativeMediaRequest;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import com.gameneko.aigame.narrative.NarrativeOption;
import com.gameneko.aigame.narrative.NarrativeInitializationQuestion;
import com.gameneko.aigame.narrative.NarrativeParseCandidate;

import java.util.List;

public record NarratedActionResponse(GameInstanceResponse game, String narration,
                                     List<NarrativeMediaRequest> mediaRequests,
                                     List<NarrativeProposalCandidate> proposals,
                                     List<NarrativeApplicationService.ProposalPreview> proposalPreviews,
                                     List<NarrativeOption> options,
                                     List<NarrativeInitializationQuestion> initializationQuestions,
                                     List<NarrativeParseCandidate> parseCandidates,
                                     List<NarrativeSnapshot.ConfirmedEvent> confirmedEvents) {
    public static NarratedActionResponse from(NarrativeApplicationService.NarratedAction result) {
        return new NarratedActionResponse(GameInstanceResponse.from(result.instance()), result.narration(),
                result.mediaRequests(), result.proposals(), result.proposalPreviews(), result.options(),
                result.initializationQuestions(), result.parseCandidates(), result.confirmedEvents());
    }
}
