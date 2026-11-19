package com.gameneko.aigame.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates initialization answers against declared fields without accepting arbitrary properties. */
public record InitializationSchema(List<InitializationField> fields) {
    public InitializationSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
        var ids = new java.util.HashSet<String>();
        for (var field : fields) if (!ids.add(field.id())) throw new IllegalArgumentException("Duplicate initialization field: " + field.id());
    }

    public ValidationResult validate(Map<String, InitializationValue> answers) {
        Map<String, InitializationValue> supplied = answers == null ? Map.of() : Map.copyOf(answers);
        var errors = new LinkedHashMap<String, String>();
        var declared = fields.stream().collect(java.util.stream.Collectors.toMap(InitializationField::id, field -> field));
        supplied.keySet().stream().filter(id -> !declared.containsKey(id))
                .forEach(id -> errors.put(id, "field_not_supported"));
        for (var field : fields) {
            InitializationValue value = supplied.get(field.id());
            if (value == null) {
                if (field.required()) errors.putIfAbsent(field.id(), "field_required");
                continue;
            }
            if (!matchesType(field.type(), value)) {
                errors.put(field.id(), "field_type_invalid");
                continue;
            }
            if (value instanceof InitializationValue.IntegerNumber number) {
                if (field.minimum() != null && number.value() < field.minimum()
                        || field.maximum() != null && number.value() > field.maximum()) errors.put(field.id(), "field_out_of_range");
            }
            if (value instanceof InitializationValue.Text text && !field.options().isEmpty()
                    && !field.options().contains(text.value())) errors.put(field.id(), "field_option_invalid");
        }
        return new ValidationResult(errors.isEmpty(), errors);
    }

    private static boolean matchesType(InitializationField.ValueType type, InitializationValue value) {
        return switch (type) {
            case TEXT -> value instanceof InitializationValue.Text;
            case INTEGER -> value instanceof InitializationValue.IntegerNumber;
            case DECIMAL -> value instanceof InitializationValue.DecimalNumber || value instanceof InitializationValue.IntegerNumber;
            case BOOLEAN -> value instanceof InitializationValue.Flag;
        };
    }

    public record ValidationResult(boolean accepted, Map<String, String> errors) {
        public ValidationResult { errors = Map.copyOf(errors); }
    }
}
