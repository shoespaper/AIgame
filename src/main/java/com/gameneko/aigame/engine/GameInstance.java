package com.gameneko.aigame.engine;

import java.util.Objects;

/** 保存本局的角色、世界和可选引擎状态。
 * @param gameId 游戏标识
 * @param worldId 世界书标识
 * @param contentVersion 世界书版本
 * @param rulesetId 可空的引擎标识
 * @param rulesetVersion 可空的引擎版本
 * @param lifecycle 游戏生命周期
 * @param revision 当前权威修订号
 * @param worldState 当前世界状态
 * @param character 已确认的通用角色资料
 * @param rulesetState 可空的引擎规则状态
 * @param <S> 引擎状态类型
 */
public record GameInstance<S extends GameState>(
        String gameId,
        String worldId,
        String contentVersion,
        String rulesetId,
        String rulesetVersion,
        Lifecycle lifecycle,
        long revision,
        WorldState worldState,
        CharacterProfile character,
        S rulesetState
) {
    /** 验证实例完整性及规则标识与规则状态的一致性。 */
    public GameInstance {
        requireText(gameId, "gameId");
        requireText(worldId, "worldId");
        requireText(contentVersion, "contentVersion");
        if (rulesetState != null) {
            requireText(rulesetId, "rulesetId");
            requireText(rulesetVersion, "rulesetVersion");
        } else if (rulesetId != null || rulesetVersion != null) {
            throw new IllegalArgumentException("An engine id and version require engine state");
        }
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(worldState, "worldState");
        Objects.requireNonNull(character, "character");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
    }

    /** 使用默认角色构建纯叙事实例。
     * @param gameId 游戏标识
     * @param worldState 初始世界状态
     */
    public static GameInstance<GameState> narrativeOnly(String gameId, WorldState worldState) {
        return narrativeOnly(gameId, worldState, CharacterProfile.defaults());
    }

    /** 构建未选择引擎的游戏实例。
     * @param gameId 游戏标识
     * @param worldState 初始世界状态
     * @param character 玩家确认的角色资料
     */
    public static GameInstance<GameState> narrativeOnly(String gameId, WorldState worldState, CharacterProfile character) {
        return new GameInstance<>(gameId, worldState.worldId(), worldState.contentVersion(),
                null, null, Lifecycle.INITIALIZED, worldState.revision(), worldState, character, null);
    }

    /** 构建带规则状态的游戏实例。
     * @param gameId 游戏标识
     * @param worldState 初始世界状态
     * @param character 玩家确认的角色资料
     * @param rulesetId 引擎标识
     * @param rulesetVersion 引擎版本
     * @param rulesetState 初始引擎状态
     * @param <S> 引擎状态类型
     */
    public static <S extends GameState> GameInstance<S> initialized(String gameId, WorldState worldState,
                                                                  CharacterProfile character, String rulesetId,
                                                                  String rulesetVersion, S rulesetState) {
        return new GameInstance<>(gameId, worldState.worldId(), worldState.contentVersion(), rulesetId,
                rulesetVersion, Lifecycle.INITIALIZED, worldState.revision(), worldState, character, rulesetState);
    }

    /** 保留角色资料并应用结算后的世界与规则状态。
     * @param world 更新后的世界状态，带已推进的修订号
     * @param state 更新后的规则状态，纯叙事时为空
     * @param <T> 更新后的引擎状态类型
     */
    public <T extends GameState> GameInstance<T> withProgress(WorldState world, T state) {
        return new GameInstance<>(gameId, worldId, contentVersion, rulesetId, rulesetVersion,
                Lifecycle.ACTIVE, world.revision(), world, character, state);
    }

    /** 校验必须存在的标识文本。
     * @param value 标识值
     * @param field 字段名称
     */
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public enum Lifecycle { INITIALIZED, ACTIVE, PAUSED, COMPLETED }
}
