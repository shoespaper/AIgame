package com.gameneko.aigame.api;

import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.engine.PriceModifier;
import com.gameneko.aigame.engine.TimedEvent;
import com.gameneko.aigame.engine.WorldEvent;

import java.util.List;
import java.util.Map;

/** API 返回的引擎中立世界状态。 */
public record WorldInitializationResponse(
        String worldId,
        String contentVersion,
        long revision,
        String setting,
        String settingDescription,
        String location,
        String currentRegion,
        String mainQuest,
        long day,
        String time,
        Map<String, Boolean> flags,
        String originScene,
        List<TimedEvent> deadlineEvents,
        List<WorldEvent> worldEvents,
        Map<PriceModifier.Category, PriceModifier> activePriceModifiers
) {
    /** 从权威快照创建世界响应。
     * @param state 当前权威世界状态
     */
    public static WorldInitializationResponse from(WorldState state) {
        return new WorldInitializationResponse(
                state.worldId(), state.contentVersion(), state.revision(),
                state.setting(), state.settingDescription(), state.location(), state.currentRegion(),
                state.mainQuest(), state.day(), state.time(),
                state.flags(), state.originScene(), state.deadlineEvents(), state.worldEvents(), state.activePriceModifiers());
    }
}
