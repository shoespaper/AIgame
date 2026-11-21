package com.gameneko.aigame.api;

import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.narrative.NarrativeMediaRequest;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;
import com.gameneko.aigame.narrative.NarrativeOption;
import com.gameneko.aigame.narrative.NarrativeInitializationQuestion;
import com.gameneko.aigame.narrative.NarrativeParseCandidate;

import java.util.List;

public record NarrationHttpResponse(String gameId, long revision, String narration,
                                    List<NarrativeMediaRequest> mediaRequests,
                                    List<NarrativeProposalCandidate> proposals,
                                    List<NarrativeApplicationService.ProposalPreview> proposalPreviews,
                                    List<NarrativeOption> options,
                                    List<NarrativeInitializationQuestion> initializationQuestions,
                                    List<NarrativeParseCandidate> parseCandidates) {
    /** 将 provider 的结构化叙事完整映射为 HTTP 响应。 */
    /** @param gameId 当前游戏标识 */
    /** @param revision 本次生成所依据的存档版本 */
    /** @param response provider 返回的叙事候选 */
    /** @param previews 服务端校验后的提案预览 */
    public static NarrationHttpResponse from(String gameId, long revision,
                                             com.gameneko.aigame.narrative.NarrativeResponse response,
                                             List<NarrativeApplicationService.ProposalPreview> previews) {
        return new NarrationHttpResponse(gameId, revision, response.narration(), response.mediaRequests(),
                response.proposals(), previews, response.options(), response.initializationQuestions(), response.parseCandidates());
    }
}
