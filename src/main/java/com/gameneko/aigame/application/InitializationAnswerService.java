package com.gameneko.aigame.application;

import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/** Parses the small deterministic answer format and validates it against the composed schema. */
@Service
public final class InitializationAnswerService {
    private final GameInitializationSchemaService schemas;

    public InitializationAnswerService(WorldBookCatalog worlds, GameEngineFactory engineFactory) {
        this.schemas = new GameInitializationSchemaService(worlds, engineFactory);
    }

    public InitializationSchema schema(GameSelection selection) { return schemas.schema(selection); }

    public ValidationResult parseAndValidate(GameSelection selection, String input) {
        var composed = schema(selection);
        var answers = parse(input, composed);
        var validation = composed.validate(answers);
        return new ValidationResult(answers, validation.accepted(), validation.errors());
    }

    /** Accepts key=value pairs separated by commas, semicolons, or newlines. */
    private Map<String, InitializationValue> parse(String input, InitializationSchema schema) {
        var answers = new HashMap<String, InitializationValue>();
        if (input == null || input.isBlank()) return answers;
        for (String part : input.split("[,;，；\\r\\n]+")) {
            int separator = part.indexOf('=');
            if (separator < 0) separator = part.indexOf(':');
            if (separator <= 0) continue;
            String key = part.substring(0, separator).trim();
            String value = part.substring(separator + 1).trim();
            if (key.isBlank()) continue;
            var field = schema == null ? null : schema.fields().stream()
                    .filter(candidate -> candidate.id().equals(key)).findFirst().orElse(null);
            answers.put(key, typedValue(value, field));
        }
        return Map.copyOf(answers);
    }

    private static InitializationValue typedValue(String value, com.gameneko.aigame.engine.InitializationField field) {
        if (field == null) return new InitializationValue.Text(value);
        try {
            return switch (field.type()) {
                case INTEGER -> new InitializationValue.IntegerNumber(Long.parseLong(value));
                case DECIMAL -> new InitializationValue.DecimalNumber(Double.parseDouble(value));
                case BOOLEAN -> new InitializationValue.Flag(Boolean.parseBoolean(value));
                case TEXT -> new InitializationValue.Text(value);
            };
        } catch (NumberFormatException ignored) {
            return new InitializationValue.Text(value);
        }
    }

    public record ValidationResult(Map<String, InitializationValue> answers,
                                   boolean accepted, Map<String, String> errors) {
        public ValidationResult {
            answers = Map.copyOf(answers);
            errors = Map.copyOf(errors);
        }
    }
}
