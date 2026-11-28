package com.gameneko.aigame.api;

import com.gameneko.aigame.application.NarrativeApplicationService;
import com.gameneko.aigame.narrative.NarrativeSnapshot;

import java.util.List;

/** 叙事回合 HTTP 响应：权威实例、叙事文本、候选行动与本次结算确认事件。
 * @param game 已确认的游戏状态及当前可用引擎行动
 * @param narration GM 描写或系统回执文本
 * @param options 玩家下一步的纯文本候选行动
 * @param confirmedEvents 本轮已确认的事件
 * @param receiptOnly 是否只有系统回执，没有 GM 描写
 */
public record NarrationHttpResponse(GameInstanceResponse game, String narration, List<String> options,
                                    List<NarrativeSnapshot.ConfirmedEvent> confirmedEvents, boolean receiptOnly) {
    /** 将应用层叙事回合映射为 HTTP 响应。 */
    /** @param result 应用层返回的叙事回合 */
    public static NarrationHttpResponse from(NarrativeApplicationService.NarratedTurn result) {
        return new NarrationHttpResponse(GameInstanceResponse.from(result.instance(), List.of(), result.actionChoices()), result.narration(),
                result.options(), result.confirmedEvents(), result.receiptOnly());
    }
}
