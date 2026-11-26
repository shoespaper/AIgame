package com.gameneko.aigame.content;

import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;

import java.util.List;
import java.util.Map;

/** 保存世界书共有的元数据和开局字段定义。
 * @param worldId 世界书标识
 * @param contentVersion 世界内容版本
 * @param nameHint 世界展示名称
 * @param descriptionHint 世界叙事说明
 */
public record WorldDefinition(String worldId, String contentVersion, String nameHint, String descriptionHint) {
    /** 返回世界书拥有的共享角色字段和世界开场字段 schema。
     * @return 世界书共享初始化字段定义
     */
    public InitializationSchema initializationSchema() {
        return new InitializationSchema(List.of(
                new InitializationField("characterName", "角色名字", InitializationField.ValueType.TEXT,
                        true, List.of(), null, null, "请输入主角名字"),
                new InitializationField("age", "年龄", InitializationField.ValueType.INTEGER,
                        true, List.of(), 1L, 120L, "请输入主角年龄"),
                new InitializationField("gender", "性别", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "你的角色是什么性别？不想指定可以跳过"),
                new InitializationField("name", "世界名称", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "这个世界叫什么名字？"),
                new InitializationField("description", "世界描述", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "如何概括这个世界的背景？"),
                new InitializationField("location", "开场地点", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "故事从哪里开始？"),
                new InitializationField("region", "区域", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "用简短文本描述开场地点所属的区域，可省略。"),
                new InitializationField("time", "时间", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "故事从一天中的什么时候开始？"),
                new InitializationField("mainQuest", "主线方向", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "角色目前有什么主要目标？"),
                new InitializationField("originScene", "开场场景", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "还有什么开场设定想补充？")));
    }

    /** 返回世界书共享必填角色字段的兼容默认值。
     * @return 类型化的世界书默认字段值
     */
    public Map<String, InitializationValue> defaultInitializationAnswers() {
        return Map.of("characterName", new InitializationValue.Text("旅人"),
                "age", new InitializationValue.IntegerNumber(16),
                "gender", new InitializationValue.Text("未定"));
    }

}
