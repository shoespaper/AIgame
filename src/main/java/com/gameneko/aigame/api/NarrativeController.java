package com.gameneko.aigame.api;

import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for read-only narration and separately approved proposal application. */
@RestController
@RequestMapping("/api/games")
public final class NarrativeController {
    private final NarrativeApplicationService narrativeService;

    public NarrativeController(NarrativeApplicationService narrativeService) {
        this.narrativeService = narrativeService;
    }

    @PostMapping("/{gameId}/narration")
    public NarrationHttpResponse narrate(@PathVariable String gameId,
                                         @RequestBody NarrationHttpRequest request) {
        if (request == null) throw new IllegalArgumentException("Narrative request is required");
        var response = narrativeService.narrate(gameId, request.expectedRevision(), request.playerInput(), request.locale());
        var previews = new java.util.ArrayList<NarrativeApplicationService.ProposalPreview>();
        for (int index = 0; index < response.proposals().size(); index++) {
            try {
                previews.add(narrativeService.previewProposal(gameId, request.expectedRevision(), response.proposals().get(index), index));
            } catch (IllegalArgumentException exception) {
                previews.add(new NarrativeApplicationService.ProposalPreview(response.proposals().get(index).type(), false, exception.getMessage()));
            }
        }
        return NarrationHttpResponse.from(gameId, request.expectedRevision(), response, previews);
    }

    @PostMapping("/{gameId}/narration/proposals")
    public GameInstanceResponse applyProposal(@PathVariable String gameId,
                                              @RequestBody NarrativeProposalRequest request) {
        if (request == null || request.proposal() == null) throw new IllegalArgumentException("Proposal is required");
        return GameInstanceResponse.from(narrativeService.applyProposal(gameId, request.expectedRevision(), request.proposal(), request.proposalIndex()));
    }

    public record NarrativeProposalRequest(long expectedRevision, int proposalIndex,
                                           NarrativeProposalCandidate proposal) {}
}
