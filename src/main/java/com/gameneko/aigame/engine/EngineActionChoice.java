package com.gameneko.aigame.engine;

/** 由引擎根据当前状态提供的玩家行动。
 * @param id 当前对局内的行动标识
 * @param label 展示给玩家并用于结算后叙事的文字
 * @param intent 玩家选择后由引擎校验的动作意图
 */
public record EngineActionChoice(String id, String label, ActionIntent intent) {}
