package com.gameneko.aigame.engine;

import java.util.List;

/** One declared initialization field; unknown fields are rejected by the schema. */
public record InitializationField(String id, String label, ValueType type, boolean required,
                                  List<String> options, Long minimum, Long maximum, String prompt) {
    public InitializationField {
        if (id == null || !id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid initialization field id");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Initialization field label is required");
        if (type == null) throw new IllegalArgumentException("Initialization field type is required");
        options = options == null ? List.of() : List.copyOf(options);
        prompt = prompt == null ? "" : prompt;
        if (minimum != null && maximum != null && minimum > maximum) throw new IllegalArgumentException("Invalid field range");
        if (!options.isEmpty() && type != ValueType.TEXT) throw new IllegalArgumentException("Options are only supported for text fields");
    }

    public enum ValueType { TEXT, INTEGER, DECIMAL, BOOLEAN }
}
