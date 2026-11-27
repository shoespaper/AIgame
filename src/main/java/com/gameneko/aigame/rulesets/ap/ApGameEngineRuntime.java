package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.rulesets.ap.ApContent.AbilityDefinition;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.CharacterProfile;
import com.gameneko.aigame.engine.ConfirmedEvent;
import com.gameneko.aigame.engine.EngineActionChoice;
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
import java.util.stream.Collectors;

/** 为 AP 规则提供引擎中立接口所需的初始化、动作校验与状态投影适配。 */
public final class ApGameEngineRuntime implements GameEngineRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApProjection> {
    private static final int MAX_QUEST_OFFERS = 4;
    private static final List<SupportedAction.Parameter> QUEST_OFFER_PARAMETERS = List.of(
            new SupportedAction.Parameter("questId", "text", "任务实例标识，同一委托沿用原标识", true),
            new SupportedAction.Parameter("questTemplateId", "text", "任务素材标识，有模板时省略则取任务实例标识", false),
            new SupportedAction.Parameter("questTitle", "text", "任务标题，无模板时必填", false),
            new SupportedAction.Parameter("questDescription", "text", "任务描述", false),
            new SupportedAction.Parameter("questRegion", "text", "任务区域", false),
            new SupportedAction.Parameter("deadlineDays", "integer", "期限天数，无模板时省略", false));
    private static final List<SupportedAction.Parameter> BATCH_QUEST_OFFER_PARAMETERS = batchOfferParameters();
    private static final Set<String> BATCH_QUEST_OFFER_FIELDS = BATCH_QUEST_OFFER_PARAMETERS.stream()
            .map(SupportedAction.Parameter::name).collect(Collectors.toUnmodifiableSet());

    private final ApContent content;
    private final JsonNode materials;
    private final JsonNode rules;
    private final ApRulesetRuntime runtime;

    /** 创建用于加载及校验开局配置的 AP 运行时。 */
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

    /** 返回 AP 引擎可用的默认职业与该世界支持的默认难度。
     * @return AP 引擎拥有的类型化默认字段值
     */
    @Override
    public Map<String, InitializationValue> defaultInitializationAnswers() {
        InitializationSchema schema = initializationSchema();
        InitializationField archetypes = schema.fields().stream().filter(field -> "archetype".equals(field.id())).findFirst().orElseThrow();
        InitializationField difficulty = schema.fields().stream().filter(field -> "difficulty".equals(field.id())).findFirst().orElseThrow();
        String defaultDifficulty = difficulty.options().contains("adventure") ? "adventure" : difficulty.options().getFirst();
        return Map.of("archetype", new InitializationValue.Text(archetypes.options().getFirst()),
                "difficulty", new InitializationValue.Text(defaultDifficulty));
    }

    /** 返回 AP 引擎专属初始化字段及其合法选项。
     * @return AP 初始化 schema
     */
    @Override
    public InitializationSchema initializationSchema() {
        Map<String, String> difficultyLabels = ApRulesetRuntime.difficultyPresets().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().label()));
        return new InitializationSchema(List.of(
                new InitializationField("archetype", "职业", InitializationField.ValueType.TEXT, true,
                        List.of("swordsman", "mage", "ranger", "alchemist"), null, null, "",
                        Map.of("swordsman", "剑士", "mage", "法师", "ranger", "游侠", "alchemist", "炼金术士")),
                new InitializationField("difficulty", "难度", InitializationField.ValueType.TEXT, true,
                        difficultyLabels.keySet().stream().sorted().toList(), null, null, "", difficultyLabels)));
    }

    /** 校验并创建 AP 初始状态。
     * @param character 玩家确认的通用角色资料
     * @param config 已确认的 AP 配置
     * @return AP 初始规则状态
     */
    @Override
    public ApRulesetRuntime.ApState initialize(CharacterProfile character, Map<String, InitializationValue> config) {
        InitializationSchema.ValidationResult validation = initializationSchema().validate(config);
        if (!validation.accepted()) throw new IllegalArgumentException("Invalid AP initialization fields: " + validation.errors());
        String archetype = ((InitializationValue.Text) config.get("archetype")).value();
        String difficulty = ((InitializationValue.Text) config.get("difficulty")).value();
        ApRulesetRuntime.ApState state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                character.name(), character.age(), character.gender(), archetype, difficulty), content);
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
                new SupportedAction("accept_quest", "仅接取当前快照 engineProjection.state.systems.questLog 中状态为 AVAILABLE 的任务。"
                        + "复用条目中的 questId，禁止猜测。没有匹配时说明登记途径；有多个匹配时请玩家选定。", List.of(
                        new SupportedAction.Parameter("questId", "text", "当前可接任务的实例标识", true))),
                new SupportedAction("complete_quest", "完成一个任务。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true))),
                new SupportedAction("fail_quest", "使一个任务失败。", List.of(
                        new SupportedAction.Parameter("questId", "text", "任务标识", true),
                        new SupportedAction.Parameter("reason", "text", "失败原因", false))),
                new SupportedAction("acquire_item", "获得一件物品。", List.of(
                        new SupportedAction.Parameter("itemName", "text", "物品名称", true),
                        new SupportedAction.Parameter("itemCategory", "text", "世界道具素材标识", true),
                        new SupportedAction.Parameter("itemTier", "text", "物品品质", true))),
                new SupportedAction("offer_quest", "展示一项新的可接委托前先提交登记。"
                        + "templateMaterials 为 null 时省略 questTemplateId 和 deadlineDays，提供标题和内容，由 AP 使用通用委托规则。"
                        + "接取按钮由引擎生成。传闻和暂未开放的委托无需登记。", QUEST_OFFER_PARAMETERS),
                new SupportedAction("offer_quests", "一次发布 2 至 4 项新委托。参数沿用 offer_quest，"
                        + "每项字段名加对应序号（1 至 4），例如 questId1、questTitle1、questId2、questTitle2。"
                        + "从 1 连续编号，每组 questId 必填。已经登记的委托直接沿用，无需再次提交。", BATCH_QUEST_OFFER_PARAMETERS));
    }

    /** 为每一项批量发布的委托生成带序号的结构化参数。 */
    private static List<SupportedAction.Parameter> batchOfferParameters() {
        List<SupportedAction.Parameter> parameters = new ArrayList<>();
        for (int number = 1; number <= MAX_QUEST_OFFERS; number++) {
            for (SupportedAction.Parameter field : QUEST_OFFER_PARAMETERS) {
                parameters.add(new SupportedAction.Parameter(field.name() + number, field.valueType(),
                        field.description(), number <= 2 && field.required()));
            }
        }
        return List.copyOf(parameters);
    }

    /** 生成当前可接委托的直接接取按钮。
     * @param state 当前权威 AP 状态
     */
    @Override
    public List<EngineActionChoice> actionChoices(ApRulesetRuntime.ApState state) {
        return state.systems().questLog().stream()
                .filter(quest -> quest.status() == ApQuest.Status.AVAILABLE)
                .map(quest -> new EngineActionChoice("accept_quest:" + quest.questId(), "接取「" + quest.title() + "」",
                        new ActionIntent("accept_quest", quest.questId(), List.of())))
                .toList();
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
        if (intent != null && "offer_quests".equalsIgnoreCase(intent.type())) {
            return offerQuests(state, intent, worldState);
        }
        ApRulesetRuntime.ApAction action = toAction(intent, state, worldState);
        int recovery = action instanceof ApRulesetRuntime.AdvanceDay
                ? ApRulesetRuntime.difficultyPresets().get(state.difficulty()).hpRecoveryBonus() : 0;
        RulesetResolution<ApRulesetRuntime.ApState, ApRulesetRuntime.ApEvent> result =
                runtime.resolve(state, action, recovery, content);
        List<ConfirmedEvent> events = result.events().stream().map(this::confirmed).toList();
        return new EngineResolution<>(result.nextState(), events,
                action instanceof ApRulesetRuntime.AdvanceDay);
    }

    /** 在同一次结算中发布多张委托，任意一张失败时不返回部分结果。
     * @param state 发布前的权威 AP 状态
     * @param intent 包含带序号任务参数的批量发布意图
     * @param worldState 当前世界状态，可为空
     */
    private EngineResolution<ApRulesetRuntime.ApState> offerQuests(ApRulesetRuntime.ApState state,
                                                                 ActionIntent intent, WorldState worldState) {
        Set<String> fields = new HashSet<>();
        List<List<ActionIntent.Argument>> groups = new ArrayList<>();
        for (int index = 0; index < MAX_QUEST_OFFERS; index++) groups.add(new ArrayList<>());
        for (ActionIntent.Argument argument : intent.arguments()) {
            String name = argument.name();
            if (!BATCH_QUEST_OFFER_FIELDS.contains(name) || !fields.add(name)) {
                throw new IllegalArgumentException("批量委托参数无效：" + name);
            }
            int last = name.length() - 1;
            groups.get(name.charAt(last) - '1').add(new ActionIntent.Argument(name.substring(0, last), argument.value()));
        }
        ApRulesetRuntime.ApState next = state;
        List<ConfirmedEvent> events = new ArrayList<>();
        for (int index = 0; index < MAX_QUEST_OFFERS; index++) {
            List<ActionIntent.Argument> group = groups.get(index);
            if (group.isEmpty() && index >= 2
                    && groups.subList(index + 1, MAX_QUEST_OFFERS).stream().allMatch(List::isEmpty)) break;
            EngineResolution<ApRulesetRuntime.ApState> offered = resolve(next,
                    new ActionIntent("offer_quest", "", group), worldState);
            next = offered.nextState();
            events.addAll(offered.events());
        }
        return new EngineResolution<>(next, events);
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

    /** 创建任务实例，奖励由登记规则或无模板局的通用规则决定。
     * @param intent 提供任务实例标识和可选叙事信息的动作
     * @param day 当前游戏天数
     * @param region 当前区域标识，用于补齐任务区域
     */
    private ApRulesetRuntime.OfferQuest offerQuest(ActionIntent intent, long day, String region) {
        String id = textArg(intent, "questId", intent.target());
        if (id.isBlank()) throw new IllegalArgumentException("任务标识无效。");
        String templateId = textArg(intent, "questTemplateId", "");
        boolean generic = materials.isMissingNode() && templateId.isBlank();
        JsonNode quest = generic ? MissingNode.getInstance()
                : material("quest_templates", templateId.isBlank() ? id : templateId);
        ApQuest.Reward reward = generic ? ApQuest.Reward.none() : new ApQuest.Reward(
                ApTemplateContent.ruleFor(quest, rules).path("parameters").path("experience").longValue(), 0, 0, 0);
        String title = textArg(intent, "questTitle", quest.path("name").asText());
        if (generic && title.isBlank()) throw new IllegalArgumentException("无模板委托需要任务标题。");
        long proposedDeadline = longArg(intent, "deadlineDays", 0);
        long deadline = generic ? 0 : proposedDeadline;
        return new ApRulesetRuntime.OfferQuest(new ApQuest(id, title,
                textArg(intent, "questDescription", quest.path("description").asText()),
                textArg(intent, "questRegion", region), ApQuest.Status.AVAILABLE, List.of(),
                reward, day, deadline, deadline, ""));
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
