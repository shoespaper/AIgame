package com.gameneko.aigame.narrative;

import java.util.List;

/** Narration output. Text and media are presentational, proposals require a second application step. */
public record NarrativeResponse(String narration, List<NarrativeMediaRequest> mediaRequests,
                                List<NarrativeProposalCandidate> proposals) {
    public NarrativeResponse {
        narration = narration == null ? "" : narration;
        mediaRequests = mediaRequests == null ? List.of() : List.copyOf(mediaRequests);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }
}
