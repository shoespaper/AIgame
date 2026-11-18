package com.gameneko.aigame.api;

import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.narrative.NarrativeMediaRequest;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;

import java.util.List;

public record NarrationHttpResponse(String gameId, long revision, String narration,
                                    List<NarrativeMediaRequest> mediaRequests,
                                    List<NarrativeProposalCandidate> proposals,
                                    List<NarrativeApplicationService.ProposalPreview> proposalPreviews) {
    public static NarrationHttpResponse from(String gameId, long revision,
                                             com.gameneko.aigame.narrative.NarrativeResponse response,
                                             List<NarrativeApplicationService.ProposalPreview> previews) {
        return new NarrationHttpResponse(gameId, revision, response.narration(), response.mediaRequests(), response.proposals(), previews);
    }
}
