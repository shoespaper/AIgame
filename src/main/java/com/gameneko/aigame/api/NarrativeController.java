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

    /** 将玩家选择的 GM 选项送入统一引擎校验与结算链路。 */
    /** @param gameId 当前游戏标识 */
    /** @param request 包含 revision、选项意图及可选自由文本的请求 */
    @PostMapping("/{gameId}/narration/options")
    public NarratedActionResponse applyOption(@PathVariable String gameId,
                                              @RequestBody NarrativeOptionRequest request) {
        if (request == null || request.option() == null) throw new IllegalArgumentException("Option is required");
        return NarratedActionResponse.from(narrativeService.applyOption(gameId, request.expectedRevision(),
                request.option(), request.playerInput(), request.locale()));
    }

    public record NarrativeProposalRequest(long expectedRevision, int proposalIndex,
                                           NarrativeProposalCandidate proposal) {}
    /** 玩家选择结构化选项的 HTTP 请求。 */
    public record NarrativeOptionRequest(long expectedRevision,
                                         com.gameneko.aigame.narrative.NarrativeOption option,
                                         String playerInput, String locale) {}
}
