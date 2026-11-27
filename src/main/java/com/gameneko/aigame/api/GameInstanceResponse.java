package com.gameneko.aigame.api;

import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.CharacterProfile;
import com.gameneko.aigame.engine.ConfirmedEvent;
import com.gameneko.aigame.engine.EngineActionChoice;

import java.util.List;

/** 返回权威游戏状态和本次直接动作的确认事件。 */
public record GameInstanceResponse(
        String gameId,
        String worldId,
        String contentVersion,
        String rulesetId,
        String rulesetVersion,
        GameInstance.Lifecycle lifecycle,
        long revision,
        WorldInitializationResponse world,
        CharacterProfile character,
        Object rulesetState,
        List<ConfirmedEvent> confirmedEvents,
        List<ActionChoice> actionChoices
) {
    /** 投影未执行引擎动作的游戏状态。
     * @param instance 当前权威游戏实例
     */
    public static GameInstanceResponse from(GameInstance<?> instance) {
        return from(instance, List.of());
    }

    /** 投影动作结算后的游戏状态和本次引擎事件。
     * @param instance 已结算的权威游戏实例
     * @param events 本次引擎确认的事实
     */
    public static GameInstanceResponse from(GameInstance<?> instance, List<ConfirmedEvent> events) {
        return from(instance, events, List.of());
    }

    /** 投影当前状态、结算事件及玩家可执行的选项。
     * @param instance 当前权威游戏实例
     * @param events 本次引擎确认的事件
     * @param choices 当前引擎提供的可执行选项
     */
    public static GameInstanceResponse from(GameInstance<?> instance, List<ConfirmedEvent> events,
                                            List<EngineActionChoice> choices) {
        return new GameInstanceResponse(instance.gameId(), instance.worldId(), instance.contentVersion(),
                instance.rulesetId(), instance.rulesetVersion(), instance.lifecycle(), instance.revision(),
                WorldInitializationResponse.from(instance.worldState()), instance.character(), instance.rulesetState(),
                List.copyOf(events), choices.stream().map(choice -> new ActionChoice(choice.id(), choice.label())).toList());
    }

    /** 公开可执行选项的标识和展示文字，动作意图由服务器保管。
     * @param id 当前对局内的行动标识
     * @param label 展示给玩家的文字
     */
    public record ActionChoice(String id, String label) {}
}
