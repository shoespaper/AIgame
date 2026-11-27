package com.gameneko.aigame.engine;

import java.util.List;
import java.util.Map;

/** Minimal contract for an optional, typed game engine selected at game creation. */
public interface GameEngineRuntime<S extends GameState, P> {
    String id();

    String version();

    /** 返回可供兼容创建入口使用的引擎字段默认答案。
     * @return 当前引擎拥有的默认初始化字段
     */
    Map<String, InitializationValue> defaultInitializationAnswers();

    /** 返回引擎配置表单的字段及约束。 */
    InitializationSchema initializationSchema();

    /** 追加依赖多个配置字段或角色资料的引擎规则。
     * @param character 已通过基础校验的角色资料
     * @param config 引擎专用配置字段
     */
    default InitializationSchema.ValidationResult validateInitialization(CharacterProfile character,
                                                                         Map<String, InitializationValue> config) {
        return new InitializationSchema.ValidationResult(true, Map.of());
    }

    /** 根据已确认的资料创建引擎初始状态。
     * @param character 通用角色资料
     * @param config 引擎专用配置字段
     */
    S initialize(CharacterProfile character, Map<String, InitializationValue> config);

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

    /** 接收世界推进后的事实并执行引擎自己的规则。
     * @param state 推进动作结算后的引擎状态
     * @param worldDay 推进后的世界天数
     * @param seed 本轮确定性随机种子
     * @param newEvents 本轮新增的世界事实
     */
    default EngineResolution<S> afterWorldAdvance(S state, long worldDay, long seed, List<WorldEvent> newEvents) {
        return new EngineResolution<>(state, List.of());
    }

    P project(S state);
}
