package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;

import com.gameneko.aigame.application.NarrativeApplicationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for a single narration endpoint: pure narration or one engine call resolved by the server. */
@RestController
@RequestMapping("/api/games")
public final class NarrativeController {
    private final NarrativeApplicationService narrativeService;

    public NarrativeController(NarrativeApplicationService narrativeService) {
        this.narrativeService = narrativeService;
    }

    /** 生成当前回合；模型输出为空 action 时纯叙事，非空时经引擎校验并结算。 */
    /** @param gameId 当前游戏标识 */
    /** @param request 包含 revision、玩家输入与语言的请求 */
    @PostMapping("/{gameId}/narration")
    public NarrationHttpResponse narrate(@PathVariable String gameId,
                                         @RequestBody NarrationHttpRequest request) {
        if (request == null) throw GameRequestException.invalid("Narrative request is required");
        return NarrationHttpResponse.from(narrativeService.narrate(gameId, request.expectedRevision(),
                request.playerInput(), request.locale(), request.history().stream()
                        .map(message -> new com.gameneko.aigame.narrative.NarrativeRequest.ConversationMessage(message.role(), message.text())).toList()));
    }
}
