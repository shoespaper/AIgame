package com.gameneko.aigame.engine;

import java.util.List;
import java.util.Map;

/** 声明一个可校验的初始化字段及其表单信息。
 * @param id 稳定字段标识
 * @param label 字段显示名称
 * @param type 值类型
 * @param required 是否必填
 * @param options 文本枚举的合法选项 ID，缺省为空列表
 * @param minimum 可空的数字下限
 * @param maximum 可空的数字上限
 * @param prompt 可空的填写提示
 * @param optionLabels 枚举选项的可选显示名称，以稳定 ID 为键
 */
public record InitializationField(String id, String label, ValueType type, boolean required,
                                  List<String> options, Long minimum, Long maximum, String prompt,
                                  Map<String, String> optionLabels) {
    /** 校验字段定义并固定可选值。 */
    public InitializationField {
        if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid initialization field id");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Initialization field label is required");
        if (type == null) throw new IllegalArgumentException("Initialization field type is required");
        options = options == null ? List.of() : List.copyOf(options);
        prompt = prompt == null ? "" : prompt;
        optionLabels = optionLabels == null ? Map.of() : Map.copyOf(optionLabels);
        if (minimum != null && maximum != null && minimum > maximum) throw new IllegalArgumentException("Invalid field range");
        if (!options.isEmpty() && type != ValueType.TEXT) throw new IllegalArgumentException("Options are only supported for text fields");
        if (!options.containsAll(optionLabels.keySet())) throw new IllegalArgumentException("Unknown labeled option");
    }

    /** 声明可直接用选项 ID 显示或无枚举选项的字段。
     * @param id 稳定字段标识
     * @param label 字段显示名称
     * @param type 值类型
     * @param required 是否必填
     * @param options 文本枚举的合法选项 ID，缺省为空列表
     * @param minimum 可空的数字下限
     * @param maximum 可空的数字上限
     * @param prompt 可空的填写提示
     */
    public InitializationField(String id, String label, ValueType type, boolean required,
                               List<String> options, Long minimum, Long maximum, String prompt) {
        this(id, label, type, required, options, minimum, maximum, prompt, Map.of());
    }

    /** 表示初始化表单支持的值类型。 */
    public enum ValueType { TEXT, INTEGER, DECIMAL, BOOLEAN }
}
