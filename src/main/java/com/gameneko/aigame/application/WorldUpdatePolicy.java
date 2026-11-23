package com.gameneko.aigame.application;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.InitializationValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 在应用层限定可写的世界字段与模板区域枚举，不依赖具体引擎。 */
final class WorldUpdatePolicy {
    private static final Set<String> FIELDS = Set.of("name", "description", "location", "region",
            "time", "mainQuest", "originScene", "visitedLocations");

    /** 禁止实例化无状态校验工具。 */
    private WorldUpdatePolicy() {}

    /** 逐字段筛选合法世界声明，未知字段、错误类型和非法区域均独立忽略。
     * @param updates GM 声明的类型化字段值
     * @param definition 当前世界模板，用于读取区域稳定标识
     * @return 可直接交给世界状态纯合并的声明
     */
    static Map<String, InitializationValue> accepted(Map<String, InitializationValue> updates, WorldDefinition definition) {
        var accepted = new LinkedHashMap<String, InitializationValue>();
        updates.forEach((field, value) -> {
            if (!FIELDS.contains(field) || !(value instanceof InitializationValue.Text text)) return;
            if ("region".equals(field) && !definition.regionMonsterTypes().containsKey(text.value())) return;
            accepted.put(field, value);
        });
        return Map.copyOf(accepted);
    }
}
