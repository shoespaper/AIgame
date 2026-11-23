package com.gameneko.aigame.narrative;

import java.util.List;

/** 服务端依据预制 schema 生成并返回给前端的初始化问题。
 * @param fieldId 待确认字段的稳定标识
 * @param prompt 向玩家展示的问题文本
 * @param options 服务端提供的建议选项，空值转为空列表
 */
public record NarrativeInitializationQuestion(String fieldId, String prompt, List<String> options) {
    /** 校验问题标识并复制选项集合。 */
    public NarrativeInitializationQuestion {
        if (fieldId == null || fieldId.isBlank()) throw new IllegalArgumentException("Question field id is required");
        prompt = prompt == null ? "" : prompt;
        options = options == null ? List.of() : List.copyOf(options);
    }
}
