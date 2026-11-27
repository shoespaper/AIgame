package com.gameneko.aigame.engine;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 校验初始化字段的存在性、类型、范围和选项。
 * @param fields 有序的表单定义
 */
public record InitializationSchema(List<InitializationField> fields) {
    /** 固定字段顺序并拒绝重复定义。 */
    public InitializationSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
        Set<String> ids = new HashSet<>();
        for (InitializationField field : fields) {
            if (!ids.add(field.id())) throw new IllegalArgumentException("Duplicate initialization field: " + field.id());
        }
    }

    /** 校验全部输入并返回各字段的错误。
     * @param answers 已转换为有限值类型的输入
     */
    public ValidationResult validate(Map<String, InitializationValue> answers) {
        Map<String, InitializationValue> supplied = answers == null ? Map.of() : answers;
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> undeclared = new HashSet<>(supplied.keySet());
        for (InitializationField field : fields) {
            undeclared.remove(field.id());
            InitializationValue value = supplied.get(field.id());
            if (value == null || value instanceof InitializationValue.Text text && field.required() && text.value().isBlank()) {
                if (field.required()) errors.put(field.id(), "field_required");
                continue;
            }
            if (!matchesType(field.type(), value)) {
                errors.put(field.id(), "field_type_invalid");
                continue;
            }
            if (value instanceof InitializationValue.IntegerNumber number
                    && outOfRange(field, BigDecimal.valueOf(number.value()))
                    || value instanceof InitializationValue.DecimalNumber decimal
                    && outOfRange(field, BigDecimal.valueOf(decimal.value()))) {
                errors.put(field.id(), "field_out_of_range");
            }
            if (value instanceof InitializationValue.Text text && !field.options().isEmpty()
                    && !field.options().contains(text.value())) errors.put(field.id(), "field_option_invalid");
        }
        undeclared.forEach(id -> errors.put(id, "field_not_supported"));
        return new ValidationResult(errors.isEmpty(), errors);
    }

    /** 检查数值是否超出声明的边界。
     * @param field 字段定义，可能没有范围约束
     * @param value 待检查的数值
     */
    private static boolean outOfRange(InitializationField field, BigDecimal value) {
        return field.minimum() != null && value.compareTo(BigDecimal.valueOf(field.minimum())) < 0
                || field.maximum() != null && value.compareTo(BigDecimal.valueOf(field.maximum())) > 0;
    }

    /** 检查值是否符合声明的类型。
     * @param type 字段的声明类型
     * @param value 待检查的值
     */
    private static boolean matchesType(InitializationField.ValueType type, InitializationValue value) {
        return switch (type) {
            case TEXT -> value instanceof InitializationValue.Text;
            case INTEGER -> value instanceof InitializationValue.IntegerNumber;
            case DECIMAL -> value instanceof InitializationValue.DecimalNumber || value instanceof InitializationValue.IntegerNumber;
            case BOOLEAN -> value instanceof InitializationValue.Flag;
        };
    }

    /** 初始化字段校验结果。
     * @param accepted 整份输入是否通过校验
     * @param errors 字段 ID 到错误码的映射
     */
    public record ValidationResult(boolean accepted, Map<String, String> errors) {
        /** 固定字段错误的快照。 */
        public ValidationResult { errors = Map.copyOf(errors); }
    }
}
