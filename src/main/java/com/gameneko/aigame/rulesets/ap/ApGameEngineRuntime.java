package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.EngineResolution;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.SupportedAction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 为 AP 规则提供引擎中立接口所需的初始化、动作校验与状态投影适配。 */
public final class ApGameEngineRuntime implements GameEngineRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApProjection> {
    private final WorldDefinition world;
    private final JsonNode materials;
    private final JsonNode rules;
    private final ApRulesetRuntime runtime = new ApRulesetRuntime();

    /** 为当前世界创建 AP 运行时，难度缺省时使用引擎默认配置。
     * @param world 世界书与模板合并后的内容
     */
    public ApGameEngineRuntime(WorldDefinition world) {
        this(world, MissingNode.getInstance(), MissingNode.getInstance());
    }

    /** 创建复用已确认模板的 AP 引擎。
     * @param world 已映射的世界素材
     * @param materials 已确认的叙事条目
     * @param rules 注册的 AP 计算规则
     */
    ApGameEngineRuntime(WorldDefinition world, JsonNode materials, JsonNode rules) {
        this.materials = materials;
        this.rules = rules;
        if (world == null) throw new IllegalArgumentException("AP requires a world definition");
        this.world = world;
    }

    /** 返回此规则引擎的稳定标识。
     * @return 引擎标识
     */
    @Override public String id() { return runtime.id(); }
    /** 返回此规则引擎的版本。
     * @return 引擎版本
     */
    @Override public String version() { return runtime.version(); }

    /** 返回 AP 引擎的初始化访谈模板，职业细节由引擎模块独立维护。
     * @return AP 专属的初始化提示词
     */
    @Override
    public String initializationPrompt() {
        var prompt = new StringBuilder("""
                你正在主持 AP 规则游戏的开局访谈。结合玩家已经说过的内容，自然地逐步了解角色。
                职业可选：swordsman（剑士，均衡近战）、mage（法师，擅长法术但体质较弱）、
                ranger（游侠，灵活的远程角色）、alchemist（炼金术师，擅长药剂辅助）。
                难度选项及其可解释的含义见下方清单；所有枚举 updates 必须使用稳定 id。
                字段清单里的“缺少的必填字段”是服务端校验结果，请继续多轮提问，直到这些字段合法齐全。
                可以从玩家一句话里确认多个字段；可按玩家要求修改已填字段。把确认值放入 updates。
                narration 用自然对话提出下一问并回应玩家；options 给出贴合本轮问题的简短建议。
                初始化期间 action 必须为 null。只输出四字段响应 JSON，未声明字段时 updates 为 {}。

                难度选项与说明：
                """);
        ApRulesetRuntime.difficultyPresets().forEach((id, difficulty) -> prompt.append(id).append("（")
                .append(difficulty.label()).append("）：").append(difficulty.description()).append("\n"));
        return prompt.toString();
    }

    /** 返回 AP 引擎可用的默认职业与该世界支持的默认难度。
     * @return AP 引擎拥有的类型化默认字段值
     */
    @Override
    public Map<String, InitializationValue> defaultInitializationAnswers() {
        var schema = initializationSchema();
        var archetypes = schema.fields().stream().filter(field -> "archetype".equals(field.id())).findFirst().orElseThrow();
        var difficulty = schema.fields().stream().filter(field -> "difficulty".equals(field.id())).findFirst().orElseThrow();
        String defaultDifficulty = difficulty.options().contains("adventure") ? "adventure" : difficulty.options().getFirst();
        return Map.of("archetype", new InitializationValue.Text(archetypes.options().getFirst()),
                "difficulty", new InitializationValue.Text(defaultDifficulty));
    }

    /** 返回 AP 引擎专属初始化字段及其合法选项。
     * @return AP 初始化 schema
     */
    @Override
    public InitializationSchema initializationSchema() {
        return new InitializationSchema(List.of(
                new InitializationField("archetype", "职业", InitializationField.ValueType.TEXT, true,
                        List.of("swordsman", "mage", "ranger", "alchemist"), null, null, "请选择职业"),
                new InitializationField("difficulty", "难度", InitializationField.ValueType.TEXT, true,
                        ApRulesetRuntime.difficultyPresets().keySet().stream().sorted().toList(), null, null, "请选择难度")));
    }

    /** 校验并创建 AP 初始状态。
     * @param answers 已通过组合 schema 校验的初始化字段
     * @return AP 初始规则状态
     */
    @Override
    public ApRulesetRuntime.ApState initialize(Map<String, InitializationValue> answers) {
        if (answers == null) throw new IllegalArgumentException("AP initialization answers are required");
        // The application validates the combined world-book + engine schema. The
        // adapter validates only fields owned by AP so shared world-book fields
        // are not rejected as unknown engine fields.
        var engineFieldIds = initializationSchema().fields().stream()
                .map(InitializationField::id).collect(java.util.stream.Collectors.toSet());
        var engineAnswers = new java.util.HashMap<String, InitializationValue>();
        answers.forEach((field, value) -> {
            if (engineFieldIds.contains(field)) engineAnswers.put(field, value);
        });
        var validation = initializationSchema().validate(engineAnswers);
        if (!validation.accepted()) throw new IllegalArgumentException("Invalid AP initialization fields: " + validation.errors());
        String name = text(answers, "characterName", "旅人");
        int age = (int) integer(answers, "age", 16);
        String gender = text(answers, "gender", "未定");
        String archetype = text(answers, "archetype", "swordsman");
        String difficulty = text(answers, "difficulty", "adventure");
        ApRulesetRuntime.ApState state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(name, age, gender, archetype, difficulty), world);
        if (state.uniqueTalent() != null && materials.isObject()) {
            int bonus = parameters("talent_types", state.uniqueTalent().type()).path("max_hp_bonus").asInt();
            return state.withStartingHealthBonus(bonus);
        }
        return state;
    }

    @Override public ActionValidation validate(ApRulesetRuntime.ApState state, ActionIntent intent) {
        try { return runtime.validate(state, toAction(intent), world); }
        catch (IllegalArgumentException exception) { return ActionValidation.reject(exception.getMessage()); }
    }

    @Override
    public List<SupportedAction> supportedActions() {
        List<String> skillRules = new ArrayList<>();
        for (JsonNode rule : rules) {
            if (List.of("skill", "spell").contains(rule.path("entity_type").asText())) {
                skillRules.add(rule.path("template_id").asText() + "（" + rule.path("rule_type").asText() + "）");
            }
        }
        return List.of(
                new SupportedAction("rest", "就地休息，恢复生命与行动点。", List.of()),
                new SupportedAction("consume_ration", "吃一份口粮恢复状态。", List.of()),
                new SupportedAction("use_potion", "使用一瓶药水。", List.of()),
                new SupportedAction("advance_day", "推进到下一天，触发世界推进。", List.of()),
                new SupportedAction("grant_experience", "获得经验值。", List.of(
                        new SupportedAction.Parameter("amount", "integer", "经验值数量", true))),
                new SupportedAction("start_battle", "开始一场遭遇。", List.of(new SupportedAction.Parameter("region", "text", "遭遇区域标识", false))),
                new SupportedAction("attack", "普通攻击。", List.of(new SupportedAction.Parameter("targetId", "text", "战斗目标标识", true))),
                new SupportedAction("use_skill", "使用已学技能，包括防御技。", List.of(new SupportedAction.Parameter("abilityId", "text", "技能实例标识", true), new SupportedAction.Parameter("targetId", "text", "战斗目标标识", false))),
                new SupportedAction("cast_spell", "施放已学法术。", List.of(new SupportedAction.Parameter("abilityId", "text", "技能实例标识", true), new SupportedAction.Parameter("targetId", "text", "战斗目标标识", false))),
                new SupportedAction("advance_battle_turn", "结束当前行动者的回合。", List.of()),
                new SupportedAction("settle_battle", "结算已结束的战斗。", List.of(new SupportedAction.Parameter("result", "text", "战斗结果枚举", true))),
                new SupportedAction("register_npc", "根据世界 NPC 类型创建角色。", List.of(new SupportedAction.Parameter("npcId", "text", "角色标识", true), new SupportedAction.Parameter("npcType", "text", "世界素材中的角色类别", true), new SupportedAction.Parameter("name", "text", "展示名称", true))),
                new SupportedAction("talk_npc", "与已登记角色交谈。", List.of(new SupportedAction.Parameter("npcId", "text", "角色标识", true))),
                new SupportedAction("supply_event", "处理已发生的补给事件。", List.of(new SupportedAction.Parameter("eventId", "text", "世界事件素材标识", true))),
                new SupportedAction("create_skill", "创作并学习技能，可用计算模板：" + String.join("、", skillRules), List.of(new SupportedAction.Parameter("abilityId", "text", "技能实例标识", true), new SupportedAction.Parameter("name", "text", "展示名称", true), new SupportedAction.Parameter("template_id", "text", "AP 计算模板标识", true))),
                new SupportedAction("defend", "在当前战斗回合消耗行动点进行防御。", List.of()),
                new SupportedAction("learn_ability", "学习一个能力。", List.of(
                        new SupportedAction.Parameter("abilityId", "text", "能力标识", true))),
                new SupportedAction("equip_item", "装备一件物品到指定槽位。", List.of(
                        new SupportedAction.Parameter("itemName", "text", "物品名称", true),
                        new SupportedAction.Parameter("slot", "text", "装备槽位", false))),
                new SupportedAction("use_item", "使用一件物品。", List.of(
                        new SupportedAction.Parameter("itemName", "text", "物品名称", true))),
                new SupportedAction("accept_quest", "接受一个任务。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true))),
                new SupportedAction("complete_quest", "完成一个任务。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true))),
                new SupportedAction("fail_quest", "使一个任务失败。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true),
                        new SupportedAction.Parameter("reason", "text", "失败原因", false))),
                new SupportedAction("claim_quest_reward", "领取任务奖励。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true),
                        new SupportedAction.Parameter("xp", "integer", "经验奖励", false),
                        new SupportedAction.Parameter("copper", "integer", "铜币奖励", false),
                        new SupportedAction.Parameter("rations", "integer", "口粮奖励", false),
                        new SupportedAction.Parameter("potions", "integer", "药水奖励", false))),
                new SupportedAction("acquire_item", "获得一件物品。", List.of(
                        new SupportedAction.Parameter("itemName", "text", "物品名称", true),
                        new SupportedAction.Parameter("itemCategory", "text", "物品类别", true),
                        new SupportedAction.Parameter("itemTier", "text", "物品品质", true))),
                new SupportedAction("offer_quest", "向玩家提供新任务。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true),
                        new SupportedAction.Parameter("questTitle", "text", "任务标题", true),
                        new SupportedAction.Parameter("questDescription", "text", "任务描述", false),
                        new SupportedAction.Parameter("questRegion", "text", "任务区域", false),
                        new SupportedAction.Parameter("deadlineDays", "integer", "期限天数", false))));
    }

    /** 校验并结算 AP 动作，推进天数时应用有效难度的生命恢复加成。
     * @param state 执行动作前的 AP 状态，包含所选难度标识
     * @param intent 待结算的引擎中立动作意图
     * @return AP 新状态及已确认的动作事件
     */
    @Override
    public EngineResolution<ApRulesetRuntime.ApState> resolve(ApRulesetRuntime.ApState state, ActionIntent intent) {
        var action = toAction(intent);
        var validation = runtime.validate(state, action, world);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        int recovery = action instanceof ApRulesetRuntime.AdvanceDay
                ? ApRulesetRuntime.difficultyPresets().get(state.difficulty()).hpRecoveryBonus() : 0;
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

    /** 按素材标识找到当前引擎已确认的数值。
     * @param group 世界模板中的素材集合
     * @param id 素材标识
     */
    private JsonNode parameters(String group, String id) {
        for (JsonNode entry : materials.path(group)) {
            if (id.equals(entry.path("id").asText())) return rules.path(entry.path("engines").path("ap").path("template_id").asText()).path("parameters");
        }
        throw new IllegalArgumentException("未知素材：" + id);
    }

    /** 将 AI 的结构化意图转换成已支持的 AP 动作。
     * @param intent 已解析的动作及其参数
     */
    private ApRulesetRuntime.ApAction toAction(ActionIntent intent) {
        if (intent == null) throw new IllegalArgumentException("Action intent is required");
        return switch (intent.type().toLowerCase(Locale.ROOT)) {
            case "rest" -> new ApRulesetRuntime.Rest();
            case "consume_ration" -> new ApRulesetRuntime.ConsumeRation();
            case "use_potion" -> new ApRulesetRuntime.UsePotion();
            case "advance_day" -> new ApRulesetRuntime.AdvanceDay();
            case "grant_experience" -> new ApRulesetRuntime.GrantExperience(longArg(intent, "amount", 0));
            case "start_battle" -> new ApRulesetRuntime.StartBattle("遭遇", textArg(intent, "region", ""), 1, 0);
            case "attack" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.ATTACK, textArg(intent, "targetId", intent.target()), "", "");
            case "use_skill" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.SKILL, textArg(intent, "targetId", ""), textArg(intent, "abilityId", intent.target()), "");
            case "cast_spell" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.SPELL, textArg(intent, "targetId", ""), textArg(intent, "abilityId", intent.target()), "");
            case "advance_battle_turn" -> new ApRulesetRuntime.AdvanceBattleTurn();
            case "settle_battle" -> new ApRulesetRuntime.SettleBattle(ApBattle.Result.valueOf(textArg(intent, "result", "").toUpperCase(Locale.ROOT)));
            case "register_npc" -> new ApRulesetRuntime.RegisterNpc(textArg(intent,"npcId",""), textArg(intent,"name",""), textArg(intent,"npcType",""), "", 0);
            case "talk_npc" -> new ApRulesetRuntime.InteractNpc(textArg(intent,"npcId",""), "talk", ApNpc.InteractionResult.NEUTRAL, 0);
            case "supply_event" -> new ApRulesetRuntime.SupplyEvent(parameters("event_templates", textArg(intent,"eventId","")).path("rations").asInt());
            case "create_skill" -> createdSkill(intent);
            case "defend" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.DEFEND, "", "", "");
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
                    List.of(), new ApQuest.Reward(materials.isObject() ? parameters("quest_templates", textArg(intent, "questId", intent.target())).path("experience").asInt() : longArg(intent, "xp", 0), longArg(intent, "copper", 0),
                    longArg(intent, "rations", 0), longArg(intent, "potions", 0)), 0,
                    longArg(intent, "deadlineDays", 0), longArg(intent, "deadlineDays", 0), ""));
            default -> throw new IllegalArgumentException("Unsupported AP intent: " + intent.type());
        };
    }

    /** 以已注册的计算模板创建可保存的具名能力。
     * @param intent 含稳定技能标识、技能名称及规则模板标识的意图
     */
    private ApRulesetRuntime.LearnAbility createdSkill(ActionIntent intent) {
        String id = textArg(intent, "abilityId", "");
        String name = textArg(intent, "name", "");
        if (!id.matches("[a-zA-Z0-9_-]{1,80}") || name.isBlank() || world.abilities().containsKey(id)) throw new IllegalArgumentException("新技能标识或名称无效");
        JsonNode rule = rules.path(textArg(intent, "template_id", ""));
        return new ApRulesetRuntime.LearnAbility(id, ApTemplateContent.ability(id, name, "", rule));
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
