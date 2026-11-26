package com.gameneko.aigame.application;

import com.gameneko.aigame.engine.InitializationValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 在应用层限定 GM 可写的世界字段和字段类型。 */
final class WorldUpdatePolicy {
    private static final Set<String> FIELDS = Set.of("name", "description", "location", "region",
            "time", "mainQuest", "originScene", "visitedLocations");

    /** 禁止实例化无状态校验工具。 */
    private WorldUpdatePolicy() {}

    /** 逐字段保留支持的文本声明，区域作为叙事文本保存。
     * @param updates GM 声明的类型化字段值
     * @return 可直接交给世界状态纯合并的声明
     */
    static Map<String, InitializationValue> accepted(Map<String, InitializationValue> updates) {
        Map<String, InitializationValue> accepted = new LinkedHashMap<>();
        updates.forEach((field, value) -> {
            if (!FIELDS.contains(field) || !(value instanceof InitializationValue.Text)) return;
            accepted.put(field, value);
        });
        return Map.copyOf(accepted);
    }
}
