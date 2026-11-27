package com.gameneko.aigame.api;

import com.gameneko.aigame.application.GameRequestException;

import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.narrative.NarrativeRequest.ConversationMessage;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 处理自由叙事和由引擎提供的直接行动选项。 */
@RestController
@RequestMapping("/api/games")
public final class NarrativeController {
    private final NarrativeApplicationService narrativeService;

    /** 注入负责叙事与结算的应用服务。
     * @param narrativeService 叙事回合和直接行动服务
     */
    public NarrativeController(NarrativeApplicationService narrativeService) {
        this.narrativeService = narrativeService;
    }

    /** 生成自由叙事回合，或先结算已选行动再生成叙事。
     * @param gameId 当前游戏标识
     * @param request 包含修订号、输入或行动标识以及叙事上下文
     */
    @PostMapping("/{gameId}/narration")
    public NarrationHttpResponse narrate(@PathVariable String gameId,
                                         @RequestBody NarrationHttpRequest request) {
        if (request == null) throw GameRequestException.invalid("Narrative request is required");
        List<ConversationMessage> history = request.history().stream()
                .map(message -> new ConversationMessage(message.role(), message.text())).toList();
        if (request.choiceId() != null) {
            return NarrationHttpResponse.from(narrativeService.choose(gameId, request.expectedRevision(),
                    request.choiceId(), request.locale(), history));
        }
        return NarrationHttpResponse.from(narrativeService.narrate(gameId, request.expectedRevision(),
                request.playerInput(), request.locale(), history));
    }
}
