package com.gameneko.aigame.engine;

import java.util.List;

/** 保存玩家确认的通用角色资料，供任意规则和叙事使用。
 * @param name 角色姓名
 * @param age 角色年龄，单位为岁，取 1 至 120
 * @param gender 玩家填写的角色性别
 * @param background 可选的角色背景，空白归一化为空字符串
 */
public record CharacterProfile(String name, int age, String gender, String background) {
    public static final long MIN_AGE = 1;
    public static final long MAX_AGE = 120;
    public static final InitializationSchema SCHEMA = new InitializationSchema(List.of(
            new InitializationField("characterName", "姓名", InitializationField.ValueType.TEXT,
                    true, List.of(), null, null, ""),
            new InitializationField("age", "年龄", InitializationField.ValueType.INTEGER,
                    true, List.of(), MIN_AGE, MAX_AGE, ""),
            new InitializationField("gender", "性别", InitializationField.ValueType.TEXT,
                    true, List.of(), null, null, ""),
            new InitializationField("background", "角色背景", InitializationField.ValueType.TEXT,
                    false, List.of(), null, null, "")));

    /** 验证通用角色资料并去除文本首尾的空白。 */
    public CharacterProfile {
        if (name == null || name.isBlank() || gender == null || gender.isBlank()
                || age < MIN_AGE || age > MAX_AGE) throw new IllegalArgumentException("Invalid character profile");
        name = name.strip();
        gender = gender.strip();
        background = background == null ? "" : background.strip();
    }

    /** 为支持默认初始化的程序调用方提供通用角色资料。 */
    public static CharacterProfile defaults() {
        return new CharacterProfile("旅人", 16, "未定", "");
    }
}
