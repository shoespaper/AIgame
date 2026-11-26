package com.gameneko.aigame.engine;

/** 保存已发生的世界事件事实。
 * @param id 本次事件唯一标识
 * @param day 事件发生的世界天数
 * @param type 事件类别
 * @param description 已发生的叙事事实
 * @param materialId 世界事件素材标识，可为空字符串
 */
public record WorldEvent(String id, long day, Type type, String description, String materialId) {
    /** 世界推进产生的事件类别。 */
    public enum Type { DEADLINE }
}
