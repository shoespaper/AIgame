package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.EngineResolution;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;

import java.util.List;
import java.util.Map;

/** AP adapter for engine selection and initialization; action translation remains a later migration step. */
public final class ApGameEngineRuntime implements GameEngineRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApProjection> {
    private final WorldDefinition world;
    private final ApRulesetRuntime runtime = new ApRulesetRuntime();

    public ApGameEngineRuntime(WorldDefinition world) {
        if (world == null) throw new IllegalArgumentException("AP requires a world definition");
        this.world = world;
    }

    @Override public String id() { return runtime.id(); }
    @Override public String version() { return runtime.version(); }

    @Override
    public InitializationSchema initializationSchema() {
        return new InitializationSchema(List.of(
                new InitializationField("archetype", "职业", InitializationField.ValueType.TEXT, true,
                        List.of("swordsman", "mage", "ranger", "alchemist"), null, null, "请选择职业"),
                new InitializationField("difficulty", "难度", InitializationField.ValueType.TEXT, true,
                        world.difficulties().keySet().stream().sorted().toList(), null, null, "请选择难度")));
    }

    @Override
    public ApRulesetRuntime.ApState initialize(Map<String, InitializationValue> answers) {
        if (answers == null) throw new IllegalArgumentException("AP initialization answers are required");
        // The application validates the combined world-book + engine schema. The
        // adapter validates only fields owned by AP so shared world-book fields
        // are not rejected as unknown engine fields.
        var engineAnswers = new java.util.HashMap<String, InitializationValue>();
        for (String field : List.of("archetype", "difficulty")) {
            if (answers.containsKey(field)) engineAnswers.put(field, answers.get(field));
        }
        var validation = initializationSchema().validate(engineAnswers);
        if (!validation.accepted()) throw new IllegalArgumentException("Invalid AP initialization fields: " + validation.errors());
        String name = text(answers, "characterName", "旅人");
        int age = (int) integer(answers, "age", 16);
        String gender = text(answers, "gender", "未定");
        String archetype = text(answers, "archetype", "swordsman");
        String difficulty = text(answers, "difficulty", "adventure");
        return runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(name, age, gender, archetype, difficulty), world);
    }

    @Override public ActionValidation validate(ApRulesetRuntime.ApState state, ActionIntent intent) {
        return ActionValidation.reject("AP action translation is not available through the generic engine boundary yet");
    }

    @Override public EngineResolution<ApRulesetRuntime.ApState> resolve(ApRulesetRuntime.ApState state, ActionIntent intent) {
        throw new IllegalArgumentException("AP action translation is not available through the generic engine boundary yet");
    }

    @Override public ApRulesetRuntime.ApProjection project(ApRulesetRuntime.ApState state) { return runtime.project(state); }

    private static String text(Map<String, InitializationValue> values, String key, String fallback) {
        var value = values.get(key);
        return value instanceof InitializationValue.Text text && !text.value().isBlank() ? text.value() : fallback;
    }

    private static long integer(Map<String, InitializationValue> values, String key, long fallback) {
        var value = values.get(key);
        return value instanceof InitializationValue.IntegerNumber number ? number.value() : fallback;
    }
}
