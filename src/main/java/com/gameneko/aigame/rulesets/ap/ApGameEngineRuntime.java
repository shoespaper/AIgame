package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.EngineResolution;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.ProposalIntentConversion;

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
        try { return runtime.validate(state, toAction(intent), world); }
        catch (IllegalArgumentException exception) { return ActionValidation.reject(exception.getMessage()); }
    }

    @Override
    public ProposalIntentConversion convertProposal(String type, Map<String, String> fields, long seed) {
        if (fields == null || fields.size() > 16) return ProposalIntentConversion.reject("proposal_field_count_invalid");
        try {
            return switch (type) {
                case "gain_experience" -> {
                    requireFields(fields, List.of("amount"));
                    long amount = requiredLong(fields, "amount", 1, 250);
                    yield ProposalIntentConversion.accept(intent("grant_experience", number("amount", amount)));
                }
                case "acquire_item" -> {
                    requireFields(fields, List.of("itemName", "itemCategory", "itemTier"));
                    String category = requiredText(fields, "itemCategory", 40);
                    String tier = requiredText(fields, "itemTier", 40);
                    if (!world.itemTemplates().containsKey(category + ":" + tier)) {
                        yield ProposalIntentConversion.reject("unknown_item_template");
                    }
                    yield ProposalIntentConversion.accept(intent("acquire_item", text("itemName", requiredText(fields, "itemName", 80)),
                            text("itemCategory", category), text("itemTier", tier), number("proposalSeed", seed)));
                }
                default -> ProposalIntentConversion.reject("unsupported_ap_proposal");
            };
        } catch (IllegalArgumentException exception) {
            return ProposalIntentConversion.reject(exception.getMessage());
        }
    }

    private static ActionIntent intent(String type, ActionIntent.Argument... arguments) {
        return new ActionIntent(type, "", List.of(arguments));
    }
    private static ActionIntent.Argument text(String name, String value) {
        return new ActionIntent.Argument(name, new InitializationValue.Text(value));
    }
    private static ActionIntent.Argument number(String name, long value) {
        return new ActionIntent.Argument(name, new InitializationValue.IntegerNumber(value));
    }
    private static void requireFields(Map<String, String> fields, List<String> allowed) {
        if (!fields.keySet().equals(new java.util.HashSet<>(allowed))) throw new IllegalArgumentException("proposal_fields_invalid");
    }
    private static String requiredText(Map<String, String> fields, String name, int maxLength) {
        String value = fields.get(name);
        if (value == null || value.isBlank() || value.length() > maxLength) throw new IllegalArgumentException("proposal_field_invalid:" + name);
        return value.trim();
    }
    private static long requiredLong(Map<String, String> fields, String name, long min, long max) {
        try {
            long value = Long.parseLong(fields.get(name));
            if (value < min || value > max) throw new IllegalArgumentException("proposal_field_out_of_range:" + name);
            return value;
        } catch (NumberFormatException | NullPointerException exception) {
            throw new IllegalArgumentException("proposal_field_invalid:" + name);
        }
    }

    @Override public EngineResolution<ApRulesetRuntime.ApState> resolve(ApRulesetRuntime.ApState state, ActionIntent intent) {
        var action = toAction(intent);
        var validation = runtime.validate(state, action, world);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        int recovery = action instanceof ApRulesetRuntime.AdvanceDay advance
                ? world.difficulties().get(state.difficulty()).hpRecoveryBonus() : 0;
        var result = runtime.resolve(state, action, recovery, world);
        var events = result.events().stream()
                .map(event -> new com.gameneko.aigame.engine.ConfirmedEvent("ap", event.type(),
                        event.type() + ":" + event.amount())).toList();
        return new EngineResolution<>(result.nextState(), events,
                action instanceof ApRulesetRuntime.AdvanceDay);
    }

    @Override
    public ApRulesetRuntime.ApState afterWorldAdvance(ApRulesetRuntime.ApState state, long worldDay,
                                                       long seed, List<com.gameneko.aigame.engine.WorldEvent> events) {
        var next = runtime.resolve(state, new ApRulesetRuntime.TickNpcs(seed, worldDay), 0, world).nextState();
        for (var event : events) {
            var effects = event.effects();
            if (effects.rationsBonus() != 0) next = next.withResources(next.resources().add(0, effects.rationsBonus(), 0));
            if (effects.npcMoodShift() != 0) {
                var npcs = next.systems().npcs().stream().map(npc -> npc.shiftMood(effects.npcMoodShift())).toList();
                next = next.withSystems(next.systems().withNpcs(npcs));
            }
        }
        return next;
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

    private static ApRulesetRuntime.ApAction toAction(ActionIntent intent) {
        if (intent == null) throw new IllegalArgumentException("Action intent is required");
        return switch (intent.type().toLowerCase(java.util.Locale.ROOT)) {
            case "rest" -> new ApRulesetRuntime.Rest();
            case "consume_ration" -> new ApRulesetRuntime.ConsumeRation();
            case "use_potion" -> new ApRulesetRuntime.UsePotion();
            case "advance_day" -> new ApRulesetRuntime.AdvanceDay();
            case "grant_experience" -> new ApRulesetRuntime.GrantExperience(longArg(intent, "amount", 0));
            case "learn_ability" -> new ApRulesetRuntime.LearnAbility(textArg(intent, "abilityId", intent.target()));
            case "equip_item" -> new ApRulesetRuntime.EquipItem(textArg(intent, "itemName", intent.target()), textArg(intent, "slot", "weapon"));
            case "use_item" -> new ApRulesetRuntime.UseItem(textArg(intent, "itemName", intent.target()));
            case "accept_quest" -> new ApRulesetRuntime.AcceptQuest(textArg(intent, "questId", intent.target()));
            case "complete_quest" -> new ApRulesetRuntime.CompleteQuest(textArg(intent, "questId", intent.target()));
            case "fail_quest" -> new ApRulesetRuntime.FailQuest(textArg(intent, "questId", intent.target()), textArg(intent, "reason", ""));
            case "claim_quest_reward" -> new ApRulesetRuntime.ClaimQuestReward(textArg(intent, "questId", intent.target()),
                    longArg(intent, "xp", 0), longArg(intent, "copper", 0), longArg(intent, "rations", 0), longArg(intent, "potions", 0));
            case "acquire_item" -> new ApRulesetRuntime.AcquireItem(textArg(intent, "itemName", "item"),
                    textArg(intent, "itemCategory", "material"), textArg(intent, "itemTier", "common"), longArg(intent, "proposalSeed", longArg(intent, "seed", 0)));
            case "offer_quest" -> new ApRulesetRuntime.OfferQuest(new ApQuest(
                    textArg(intent, "questId", intent.target()), textArg(intent, "questTitle", "Quest"),
                    textArg(intent, "questDescription", ""), textArg(intent, "questRegion", ""), ApQuest.Status.AVAILABLE,
                    List.of(), new ApQuest.Reward(longArg(intent, "xp", 0), longArg(intent, "copper", 0),
                    longArg(intent, "rations", 0), longArg(intent, "potions", 0)), 0,
                    longArg(intent, "deadlineDays", 0), longArg(intent, "deadlineDays", 0), ""));
            default -> throw new IllegalArgumentException("Unsupported AP intent: " + intent.type());
        };
    }

    private static String textArg(ActionIntent intent, String name, String fallback) {
        for (var argument : intent.arguments()) if (name.equals(argument.name()) && argument.value() instanceof InitializationValue.Text text) return text.value();
        return fallback == null ? "" : fallback;
    }

    private static long longArg(ActionIntent intent, String name, long fallback) {
        for (var argument : intent.arguments()) if (name.equals(argument.name())) {
            if (argument.value() instanceof InitializationValue.IntegerNumber number) return number.value();
            if (argument.value() instanceof InitializationValue.Text text) {
                try { return Long.parseLong(text.value()); }
                catch (NumberFormatException ignored) { return fallback; }
            }
        }
        return fallback;
    }
}
