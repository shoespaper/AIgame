package com.gameneko.aigame.engine;

import java.util.List;

/** Engine-neutral action candidate. It is untrusted until the selected engine validates it. */
public record ActionIntent(String type, String target, List<Argument> arguments) {
    public ActionIntent {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("Intent type is required");
        type = type.trim();
        target = target == null ? "" : target;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    public record Argument(String name, InitializationValue value) {
        public Argument {
            if (name == null || name.isBlank() || value == null) throw new IllegalArgumentException("Intent argument name and value are required");
        }
    }
}
