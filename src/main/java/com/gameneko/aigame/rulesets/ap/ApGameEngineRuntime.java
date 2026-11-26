package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.rulesets.ap.ApContent.AbilityDefinition;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.ConfirmedEvent;
import com.gameneko.aigame.engine.EngineResolution;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.RulesetResolution;
import com.gameneko.aigame.engine.SupportedAction;
import com.gameneko.aigame.engine.WorldEvent;
import com.gameneko.aigame.engine.WorldState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 为 AP 规则提供引擎中立接口所需的初始化、动作校验与状态投影适配。 */
public final class ApGameEngineRuntime implements GameEngineRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApProjection> {
    private final ApContent content;
    private final JsonNode materials;
    private final JsonNode rules;
    private final ApRulesetRuntime runtime;

    /** 创建用于开局访谈的 AP 运行时。 */
    public ApGameEngineRuntime() {
        this(ApContent.empty(), MissingNode.getInstance(), MissingNode.getInstance());
    }

    /** 创建复用已确认模板的 AP 引擎。
     * @param content 已解码的 AP 专用素材
     * @param materials 已确认的叙事条目
     * @param rules 注册的 AP 计算规则
     */
    ApGameEngineRuntime(ApContent content, JsonNode materials, JsonNode rules) {
        this.materials = materials;
        this.rules = rules;
        this.runtime = new ApRulesetRuntime(rules);
        this.content = content;
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
        ApRulesetRuntime.ApState state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(name, age, gender, archetype, difficulty), content);
        if (state.uniqueTalent() != null && materials.isObject()) {
            int bonus = ApTemplateContent.ruleFor(material("talent_types", state.uniqueTalent().type()), rules)
                    .path("parameters").path("max_hp_bonus").intValue();
            return state.withStartingHealthBonus(bonus);
        }
        return state;
    }

    /** 返回 AP 可执行动作和模型创作能力时可引用的模板。 */
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
                new SupportedAction("start_battle", "场景发生遭遇时，选择符合叙事的怪物素材。由 AP 按模板生成并结算。",
                        List.of(new SupportedAction.Parameter("monsterId", "text", "世界素材 monster_types 中的怪物 ID", true))),
                new SupportedAction("attack", "普通攻击。", List.of(new SupportedAction.Parameter("targetId", "text", "战斗目标标识", true))),
                new SupportedAction("use_skill", "使用已学技能，包括防御技。", List.of(new SupportedAction.Parameter("abilityId", "text", "技能实例标识", true), new SupportedAction.Parameter("targetId", "text", "战斗目标标识", false))),
                new SupportedAction("cast_spell", "施放已学法术。", List.of(new SupportedAction.Parameter("abilityId", "text", "技能实例标识", true), new SupportedAction.Parameter("targetId", "text", "战斗目标标识", false))),
                new SupportedAction("advance_battle_turn", "结束玩家回合，由敌方依次进行普通攻击。", List.of()),
                new SupportedAction("settle_battle", "结算已结束的战斗。", List.of(new SupportedAction.Parameter("result", "text", "战斗结果枚举", true))),
                new SupportedAction("register_npc", "根据世界 NPC 类型创建角色。", List.of(new SupportedAction.Parameter("npcId", "text", "角色标识", true), new SupportedAction.Parameter("npcType", "text", "世界素材中的角色类别", true), new SupportedAction.Parameter("name", "text", "展示名称", true))),
                new SupportedAction("talk_npc", "与已登记角色交谈。", List.of(new SupportedAction.Parameter("npcId", "text", "角色标识", true))),
                new SupportedAction("supply_event", "处理已发生的补给事件。", List.of(new SupportedAction.Parameter("eventId", "text", "世界事件素材标识", true))),
                new SupportedAction("create_skill", "创作并学习技能或法术。可用模板：" + String.join("、", skillRules), List.of(
                        new SupportedAction.Parameter("abilityId", "text", "新能力的实例标识，施放时使用此标识", true),
                        new SupportedAction.Parameter("name", "text", "展示名称", true),
                        new SupportedAction.Parameter("description", "text", "能力的叙事描述", false),
                        new SupportedAction.Parameter("template_id", "text", "已注册的 AP 计算模板标识", true))),
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
                new SupportedAction("acquire_item", "获得一件物品。", List.of(
                        new SupportedAction.Parameter("itemName", "text", "物品名称", true),
                        new SupportedAction.Parameter("itemCategory", "text", "世界道具素材标识", true),
                        new SupportedAction.Parameter("itemTier", "text", "物品品质", true))),
                new SupportedAction("offer_quest", "向玩家提供新任务。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true),
                        new SupportedAction.Parameter("questTemplateId", "text", "世界任务素材标识，省略时取任务标识", false),
                        new SupportedAction.Parameter("questTitle", "text", "任务标题，省略时沿用素材名称", false),
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
        return resolve(state, intent, null);
    }

    /** 在 AP 入口解释结构化意图并执行一次校验和结算。
     * @param state 当前 AP 角色状态
     * @param intent 待结算的结构化动作
     * @param worldState 当前世界事实，直接使用引擎时可为空
     */
    @Override
    public EngineResolution<ApRulesetRuntime.ApState> resolve(ApRulesetRuntime.ApState state, ActionIntent intent,
                                                            WorldState worldState) {
        if (state == null) throw new IllegalArgumentException("AP 角色状态缺失");
        ApRulesetRuntime.ApAction action = toAction(intent, state, worldState);
        int recovery = action instanceof ApRulesetRuntime.AdvanceDay
                ? ApRulesetRuntime.difficultyPresets().get(state.difficulty()).hpRecoveryBonus() : 0;
        RulesetResolution<ApRulesetRuntime.ApState, ApRulesetRuntime.ApEvent> result =
                runtime.resolve(state, action, recovery, content);
        List<ConfirmedEvent> events = result.events().stream().map(this::confirmed).toList();
        return new EngineResolution<>(result.nextState(), events,
                action instanceof ApRulesetRuntime.AdvanceDay);
    }

    /** 更新 NPC 的每日状态，并按 AP 模板解释收到的世界事件事实。
     * @param state AP 完成日结后的状态
     * @param worldDay 推进后的游戏天数
     * @param seed NPC 日结使用的确定性种子
     * @param events 本日新增的确认事件
     */
    @Override
    public EngineResolution<ApRulesetRuntime.ApState> afterWorldAdvance(ApRulesetRuntime.ApState state, long worldDay,
                                                                       long seed, List<WorldEvent> events) {
        ApRulesetRuntime.ApState next = runtime.resolve(state, new ApRulesetRuntime.TickNpcs(seed, worldDay), 0, content).nextState();
        List<ConfirmedEvent> confirmed = new ArrayList<>();
        for (WorldEvent event : events) {
            JsonNode material = findMaterial("event_templates", event.materialId());
            if (material.isMissingNode()) continue;
            JsonNode rule = ApTemplateContent.ruleFor(material, rules);
            JsonNode parameters = rule.path("parameters");
            if ("grant_rations".equals(rule.path("rule_type").asText())) {
                RulesetResolution<ApRulesetRuntime.ApState, ApRulesetRuntime.ApEvent> supply =
                        runtime.resolve(next, new ApRulesetRuntime.SupplyEvent(event.id(),
                                material.path("name").asText(), parameters.path("rations").intValue()));
                next = supply.nextState();
                supply.events().stream().map(this::confirmed).forEach(confirmed::add);
            } else if ("shift_npc_mood".equals(rule.path("rule_type").asText())) {
                int delta = parameters.path("mood_delta").intValue();
                List<ApNpc> previous = next.systems().npcs();
                List<ApNpc> npcs = previous.stream().map(npc -> npc.status() == ApNpc.Status.ACTIVE
                        ? npc.shiftMood(delta) : npc).toList();
                long changed = 0;
                for (int index = 0; index < npcs.size(); index++) {
                    if (previous.get(index).mood() != npcs.get(index).mood()) changed++;
                }
                next = next.withSystems(next.systems().withNpcs(npcs));
                if (changed > 0) {
                    String outcome = delta > 0 ? "好转" : "变差";
                    confirmed.add(new ConfirmedEvent(id(), "event_npc_mood",
                            "「" + material.path("name").asText() + "」使 " + changed + " 位 NPC 的心情" + outcome + "。",
                            changed, event.id(), ""));
                }
            }
        }
        return new EngineResolution<>(next, confirmed);
    }

    /** 把 AP 结算结果转换为引擎中立的确认事件。
     * @param event AP 确认的结算事实
     */
    private ConfirmedEvent confirmed(ApRulesetRuntime.ApEvent event) {
        return new ConfirmedEvent(id(), event.type(), event.description(), event.amount(), event.subject(), event.target());
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

    /** 按精确标识查找已确认的世界素材。
     * @param group 世界模板中的素材集合
     * @param id 素材标识
     */
    private JsonNode material(String group, String id) {
        JsonNode material = findMaterial(group, id);
        if (material.isMissingNode()) throw new IllegalArgumentException("未知素材：" + id);
        return material;
    }

    /** 查询本局世界素材，找不到时返回缺失节点。
     * @param group 世界模板中的素材集合
     * @param id 素材标识，可为空
     */
    private JsonNode findMaterial(String group, String id) {
        if (id == null || id.isBlank()) return MissingNode.getInstance();
        for (JsonNode entry : materials.path(group)) {
            if (id.equals(entry.path("id").asText())) return entry;
        }
        return MissingNode.getInstance();
    }

    /** 将 AI 的结构化意图转换成已支持的 AP 动作。
     * @param intent 已解析的动作及其参数
     * @param state 当前 AP 角色状态
     * @param worldState 可空的世界状态，提供当前区域、地点和天数
     */
    private ApRulesetRuntime.ApAction toAction(ActionIntent intent, ApRulesetRuntime.ApState state, WorldState worldState) {
        if (intent == null) throw new IllegalArgumentException("Action intent is required");
        Set<String> fields = new HashSet<>();
        for (ActionIntent.Argument argument : intent.arguments()) {
            if (!fields.add(argument.name())) throw new IllegalArgumentException("动作参数重复：" + argument.name());
        }
        long day = worldState == null ? 1 : worldState.day();
        String region = worldState == null || worldState.currentRegion() == null ? "" : worldState.currentRegion();
        String location = worldState == null || worldState.location() == null ? "" : worldState.location();
        return switch (intent.type().toLowerCase(Locale.ROOT)) {
            case "rest" -> new ApRulesetRuntime.Rest();
            case "consume_ration" -> new ApRulesetRuntime.ConsumeRation();
            case "use_potion" -> itemAction(state, "potion");
            case "advance_day" -> new ApRulesetRuntime.AdvanceDay();
            case "grant_experience" -> new ApRulesetRuntime.GrantExperience(longArg(intent, "amount", 0));
            case "start_battle" -> new ApRulesetRuntime.StartBattle("遭遇", textArg(intent, "monsterId", ""));
            case "attack" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.ATTACK, textArg(intent, "targetId", intent.target()), "", "");
            case "use_skill" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.SKILL, textArg(intent, "targetId", ""), textArg(intent, "abilityId", intent.target()), "");
            case "cast_spell" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.SPELL, textArg(intent, "targetId", ""), textArg(intent, "abilityId", intent.target()), "");
            case "advance_battle_turn" -> new ApRulesetRuntime.AdvanceBattleTurn();
            case "settle_battle" -> new ApRulesetRuntime.SettleBattle(ApBattle.Result.valueOf(textArg(intent, "result", "").toUpperCase(Locale.ROOT)));
            case "register_npc" -> new ApRulesetRuntime.RegisterNpc(textArg(intent, "npcId", ""),
                    textArg(intent, "name", ""), textArg(intent, "npcType", ""), location, day);
            case "talk_npc" -> new ApRulesetRuntime.InteractNpc(textArg(intent, "npcId", ""), "talk",
                    ApNpc.InteractionResult.NEUTRAL, day, day);
            case "supply_event" -> supplyEvent(intent);
            case "create_skill" -> createdSkill(intent);
            case "defend" -> new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.DEFEND, "", "", "");
            case "learn_ability" -> learnAbility(textArg(intent, "abilityId", intent.target()));
            case "equip_item" -> new ApRulesetRuntime.EquipItem(textArg(intent, "itemName", intent.target()), textArg(intent, "slot", "weapon"));
            case "use_item" -> itemAction(state, textArg(intent, "itemName", intent.target()));
            case "accept_quest" -> new ApRulesetRuntime.AcceptQuest(textArg(intent, "questId", intent.target()));
            case "complete_quest" -> new ApRulesetRuntime.CompleteQuest(textArg(intent, "questId", intent.target()));
            case "fail_quest" -> new ApRulesetRuntime.FailQuest(textArg(intent, "questId", intent.target()), textArg(intent, "reason", ""));
            case "acquire_item" -> new ApRulesetRuntime.AcquireItem(textArg(intent, "itemName", "item"),
                    textArg(intent, "itemCategory", "material"), textArg(intent, "itemTier", "common"), longArg(intent, "proposalSeed", longArg(intent, "seed", 0)));
            case "offer_quest" -> offerQuest(intent, day, region);
            default -> throw new IllegalArgumentException("Unsupported AP intent: " + intent.type());
        };
    }

    /** 解析要发放的补给事件，补给量来自该素材关联的 AP 模板。
     * @param intent 包含补给事件素材标识的动作
     */
    private ApRulesetRuntime.SupplyEvent supplyEvent(ActionIntent intent) {
        String id = textArg(intent, "eventId", "");
        JsonNode event = material("event_templates", id);
        JsonNode rule = ApTemplateContent.ruleFor(event, rules);
        if (!"grant_rations".equals(rule.path("rule_type").asText())) {
            throw new IllegalArgumentException("事件素材没有 AP 补给规则：" + id);
        }
        int rations = rule.path("parameters").path("rations").intValue();
        return new ApRulesetRuntime.SupplyEvent(id, event.path("name").asText(id), rations);
    }

    /** 创建任务实例，奖励由该任务素材的登记规则决定。
     * @param intent 提供任务实例标识和可选叙事信息的动作
     * @param day 当前游戏天数
     * @param region 当前区域标识，用于补齐任务区域
     */
    private ApRulesetRuntime.OfferQuest offerQuest(ActionIntent intent, long day, String region) {
        String id = textArg(intent, "questId", intent.target());
        JsonNode quest = material("quest_templates", textArg(intent, "questTemplateId", id));
        long experience = ApTemplateContent.ruleFor(quest, rules).path("parameters").path("experience").longValue();
        long deadline = longArg(intent, "deadlineDays", 0);
        return new ApRulesetRuntime.OfferQuest(new ApQuest(id, textArg(intent, "questTitle", quest.path("name").asText()),
                textArg(intent, "questDescription", quest.path("description").asText()),
                textArg(intent, "questRegion", region), ApQuest.Status.AVAILABLE, List.of(),
                new ApQuest.Reward(experience, 0, 0, 0), day, deadline, deadline, ""));
    }

    /** 学习世界素材对应的能力并保存其模板标识。
     * @param id 技能或法术的素材标识
     */
    private ApRulesetRuntime.LearnAbility learnAbility(String id) {
        if (!materials.isObject()) return new ApRulesetRuntime.LearnAbility(id);
        AbilityDefinition definition = content.abilities().get(id);
        if (definition == null) throw new IllegalArgumentException("未知能力素材：" + id);
        String group = "spell".equals(definition.kind()) ? "spell_types" : "skill_types";
        JsonNode rule = ApTemplateContent.ruleFor(material(group, id), rules);
        return new ApRulesetRuntime.LearnAbility(id, definition, rule.path("template_id").asText());
    }

    /** 根据当前战斗状态选择对应的道具结算。
     * @param state 当前 AP 状态
     * @param name 背包道具名称或药水别名
     */
    private static ApRulesetRuntime.ApAction itemAction(ApRulesetRuntime.ApState state, String name) {
        if (state.battle() != null && state.battle().active()) {
            return new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.ITEM, "", "", name);
        }
        return new ApRulesetRuntime.UseItem(name);
    }

    /** 以已注册的计算模板创建可保存的具名能力。
     * @param intent 含稳定技能标识、技能名称及规则模板标识的意图
     */
    private ApRulesetRuntime.LearnAbility createdSkill(ActionIntent intent) {
        String id = textArg(intent, "abilityId", "");
        String name = textArg(intent, "name", "");
        if (!id.matches("[a-zA-Z0-9_-]{1,80}") || name.isBlank() || content.abilities().containsKey(id)) throw new IllegalArgumentException("新技能标识或名称无效");
        String templateId = textArg(intent, "template_id", "");
        JsonNode rule = rules.path(templateId);
        String description = textArg(intent, "description", "");
        return new ApRulesetRuntime.LearnAbility(id, ApTemplateContent.ability(id, name, description, rule, templateId), templateId);
    }

    /** 读取文本动作参数，缺省时使用预设值。
     * @param intent 待解析的结构化意图
     * @param name 参数名
     * @param fallback 省略参数时的默认值
     */
    private static String textArg(ActionIntent intent, String name, String fallback) {
        for (ActionIntent.Argument argument : intent.arguments()) {
            if (!name.equals(argument.name())) continue;
            if (argument.value() instanceof InitializationValue.Text text) return text.value();
            throw new IllegalArgumentException("动作参数必须是文本：" + name);
        }
        return fallback == null ? "" : fallback;
    }

    /** 读取整数动作参数，兼容直接接口提交的整数字符串。
     * @param intent 待解析的结构化意图
     * @param name 参数名
     * @param fallback 省略参数时的默认值
     */
    private static long longArg(ActionIntent intent, String name, long fallback) {
        for (ActionIntent.Argument argument : intent.arguments()) if (name.equals(argument.name())) {
            if (argument.value() instanceof InitializationValue.IntegerNumber number) return number.value();
            if (argument.value() instanceof InitializationValue.Text text) {
                try { return Long.parseLong(text.value()); }
                catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("动作参数必须是整数：" + name, exception);
                }
            }
            throw new IllegalArgumentException("动作参数必须是整数：" + name);
        }
        return fallback;
    }
}
