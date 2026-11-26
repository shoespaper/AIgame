package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.SupportedAction;

import java.util.List;

/** 根据权威快照提出动作并描写已确认的结算结果。 */
public interface NarrationService {
    /** 生成当前回合叙事；action 为纯叙事时返回 null。
     * @param request 玩家输入与已确认快照
     * @param engineActions 当前引擎可执行的动作清单，纯叙事世界为空列表
     */
    NarrativeResponse narrate(NarrativeRequest request, List<SupportedAction> engineActions);

    /** 生成游戏开局的首段叙事。
     * @param request 包含已确认快照的开局叙事请求
     * @param engineActions 当前引擎可执行的动作清单，纯叙事世界为空列表
     */
    NarrativeResponse opening(NarrativeRequest request, List<SupportedAction> engineActions);

    /** 根据结算后的状态和本轮确认事件生成只读叙事。
     * @param request 包含玩家行动和结算后快照的请求
     */
    NarrativeResponse describeConfirmedTurn(NarrativeRequest request);
}
