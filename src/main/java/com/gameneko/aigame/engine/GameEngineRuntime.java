package com.gameneko.aigame.engine;

import java.util.Map;
import java.util.List;

/** Minimal contract for an optional, typed game engine selected at game creation. */
public interface GameEngineRuntime<S extends GameState, P> {
    String id();

    String version();

    /** 返回当前引擎专属的多轮开局访谈提示模板。
     * @return 指导叙事模型收集引擎字段的系统提示
     */
    String initializationPrompt();

    /** 返回可供兼容创建入口使用的引擎字段默认答案。
     * @return 当前引擎拥有的默认初始化字段
     */
    Map<String, InitializationValue> defaultInitializationAnswers();

    InitializationSchema initializationSchema();

    S initialize(Map<String, InitializationValue> answers);

    /** 返回当前引擎可执行的动作清单，供叙事层生成结构化意图。 */
    default List<SupportedAction> supportedActions() {
        return List.of();
    }

    /** 校验并结算动作，动作被拒绝时抛出 IllegalArgumentException。
     * @param state 当前引擎状态
     * @param intent 待校验的结构化动作意图
     */
    EngineResolution<S> resolve(S state, ActionIntent intent);

    /** 校验并结算需要世界上下文的动作。
     * @param state 当前引擎状态
     * @param intent 待校验的结构化动作意图
     * @param world 当前确认的世界状态
     */
    default EngineResolution<S> resolve(S state, ActionIntent intent, WorldState world) {
        return resolve(state, intent);
    }

    /** Applies the selected engine's response to a confirmed world day transition. */
    default S afterWorldAdvance(S state, long worldDay, long seed, java.util.List<WorldEvent> newEvents) {
        return state;
    }

    P project(S state);
}
