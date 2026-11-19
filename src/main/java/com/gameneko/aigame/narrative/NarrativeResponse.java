package com.gameneko.aigame.narrative;

import java.util.List;

/** Narration output. Text and media are presentational, proposals require a second application step. */
public record NarrativeResponse(String narration, List<NarrativeMediaRequest> mediaRequests,
                                List<NarrativeProposalCandidate> proposals,
                                List<NarrativeOption> options,
                                List<NarrativeInitializationQuestion> initializationQuestions,
                                List<NarrativeParseCandidate> parseCandidates,
                                com.gameneko.aigame.engine.ProtocolVersion protocolVersion) {
    public NarrativeResponse {
        narration = narration == null ? "" : narration;
        mediaRequests = mediaRequests == null ? List.of() : List.copyOf(mediaRequests);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        options = options == null ? List.of() : List.copyOf(options);
        initializationQuestions = initializationQuestions == null ? List.of() : List.copyOf(initializationQuestions);
        parseCandidates = parseCandidates == null ? List.of() : List.copyOf(parseCandidates);
        protocolVersion = protocolVersion == null ? com.gameneko.aigame.engine.ProtocolVersion.current() : protocolVersion;
    }

    public NarrativeResponse(String narration, List<NarrativeMediaRequest> mediaRequests,
                             List<NarrativeProposalCandidate> proposals) {
        this(narration, mediaRequests, proposals, List.of());
    }

    public NarrativeResponse(String narration, List<NarrativeMediaRequest> mediaRequests,
                             List<NarrativeProposalCandidate> proposals, List<NarrativeOption> options) {
        this(narration, mediaRequests, proposals, options, List.of(), List.of(),
                com.gameneko.aigame.engine.ProtocolVersion.current());
    }
}
