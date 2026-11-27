package com.gameneko.aigame.api;

import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.narrative.NarrativeSnapshot;

import java.util.List;

/** 叙事回合 HTTP 响应：权威实例、叙事文本、候选行动与本次结算确认事件。 */
public record NarrationHttpResponse(GameInstanceResponse game, String narration, List<String> options,
                                    List<NarrativeSnapshot.ConfirmedEvent> confirmedEvents) {
    /** 将应用层叙事回合映射为 HTTP 响应。 */
    /** @param result 应用层返回的叙事回合 */
    public static NarrationHttpResponse from(NarrativeApplicationService.NarratedTurn result) {
        return new NarrationHttpResponse(GameInstanceResponse.from(result.instance(), List.of(), result.actionChoices()), result.narration(),
                result.options(), result.confirmedEvents());
    }
}
