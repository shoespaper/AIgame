package com.gameneko.aigame.narrative;

import java.util.List;

/** Narration output. Text and media are presentational, proposals require a second application step. */
public record NarrativeResponse(String narration, List<NarrativeMediaRequest> mediaRequests,
                                List<NarrativeProposalCandidate> proposals,
                                List<NarrativeOption> options) {
    public NarrativeResponse {
        narration = narration == null ? "" : narration;
        mediaRequests = mediaRequests == null ? List.of() : List.copyOf(mediaRequests);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        options = options == null ? List.of() : List.copyOf(options);
    }

    public NarrativeResponse(String narration, List<NarrativeMediaRequest> mediaRequests,
                             List<NarrativeProposalCandidate> proposals) {
        this(narration, mediaRequests, proposals, List.of());
    }
}
