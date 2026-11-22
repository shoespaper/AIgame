package com.gameneko.aigame.narrative;

/** Builds narration from server-owned snapshots and confirmed events. */
public interface NarrationService {
    /** 生成当前回合叙事；action 为纯叙事时返回 null。
     * @param request 玩家输入与已确认快照
     * @param engineActions 当前引擎可执行的动作清单，纯叙事世界为空列表
     */
    NarrativeResponse narrate(NarrativeRequest request, java.util.List<com.gameneko.aigame.engine.SupportedAction> engineActions);

    /** 生成游戏开局的首段叙事。
     * @param request 包含已确认快照的开局叙事请求
     * @param engineActions 当前引擎可执行的动作清单，纯叙事世界为空列表
     */
    NarrativeResponse opening(NarrativeRequest request, java.util.List<com.gameneko.aigame.engine.SupportedAction> engineActions);
}
