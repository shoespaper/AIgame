package com.gameneko.aigame.rulesets.ap;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.content.WorldDefinition.AbilityMechanic;
import com.gameneko.aigame.content.WorldDefinition.AbilityDefinition;
import com.gameneko.aigame.content.WorldDefinition.DifficultyDefinition;
import com.gameneko.aigame.content.WorldDefinition.MonsterDefinition;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.RulesetResolution;
import com.gameneko.aigame.engine.RulesetRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/** 执行 AP 规则，使用不可变状态和类型化事件记录结算结果。 */
public final class ApRulesetRuntime implements RulesetRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApAction,
        ApRulesetRuntime.ApEvent, ApRulesetRuntime.ApProjection> {
    private static final Map<String, Archetype> ARCHETYPES = Map.of(
            "swordsman", new Archetype(14, 12, 8, 12, 9, 9, "短剑", "+2", "皮背心"),
            "mage", new Archetype(7, 10, 16, 8, 14, 10, "学徒法杖", "+1", "法袍"),
            "ranger", new Archetype(11, 15, 10, 10, 12, 9, "短弓", "+1", "轻便布衣"),
            "alchemist", new Archetype(9, 10, 15, 9, 11, 13, "投石索", "+1", "工作围裙"));
    private static final Archetype DEFAULT = new Archetype(10, 10, 10, 10, 10, 10, "铁匕首", "+2", "旅行衫");
    private static final Map<String, DifficultyDefinition> DEFAULT_DIFFICULTIES = Map.of(
            "story", new DifficultyDefinition(14, 50, 5, 3, 2, "叙事", "敌人很软，钱好赚，很少真的会死。用来讲故事。", 70, 70, 75, 50, 150, 130, 15),
            "adventure", new DifficultyDefinition(7, 15, 3, 2, 0, "冒险", "标准难度。会受伤，会缺钱，走错一步可能就没了。", 100, 100, 100, 100, 100, 100, 0),
            "hard", new DifficultyDefinition(5, 5, 2, 1, -1, "艰难", "敌人更硬更狠，战利品更少。每一场都要算。", 140, 130, 125, 130, 120, 80, -10),
            "nightmare", new DifficultyDefinition(3, 0, 1, 0, -2, "噩梦", "敌人远超同阶。能活下来就是运气。", 200, 180, 150, 160, 150, 50, -20));
    private static final String[] THREAT_LEVELS = {"Dormant", "Low", "Moderate", "High", "Severe", "Crisis"};
    private static final int BASIC_ATTACK_COST = 2;
    private final JsonNode rules;

    /** 创建用于 AP 底层动作的运行时。 */
    public ApRulesetRuntime() {
        this(MissingNode.getInstance());
    }

    /** 创建使用已登记规则结算模板能力的运行时。
     * @param rules 按稳定标识索引的 AP 模板，未登记时为缺失节点
     */
    ApRulesetRuntime(JsonNode rules) {
        this.rules = rules;
    }

    @Override public String id() { return "ap"; }
    @Override public String version() { return "baseline-1"; }

    public ApState initialize(ApInitializationRequest request) {
        return initialize(request, null);
    }

    /** 返回 AP 引擎拥有的难度配置。
     * @return 按难度标识索引的配置
     */
    static Map<String, DifficultyDefinition> difficultyPresets() {
        return DEFAULT_DIFFICULTIES;
    }

    /** 按职业和有效难度创建 AP 初始角色状态。
     * @param request 角色身份、职业标识和难度标识
     * @param world 世界书与模板的合并内容，可为空
     * @return 初始 AP 角色状态
     */
    public ApState initialize(ApInitializationRequest request, WorldDefinition world) {
        if (request == null) throw new IllegalArgumentException("AP initialization input is required");
        String archetype = request.archetype() == null ? "swordsman" : request.archetype().toLowerCase().trim();
        Archetype preset = ARCHETYPES.getOrDefault(archetype, DEFAULT);
        String difficulty = request.difficulty() == null ? "adventure" : request.difficulty().toLowerCase().trim();
        DifficultyDefinition diff = difficultyPresets().get(difficulty);
        if (diff == null) throw new IllegalArgumentException("Unknown difficulty: " + difficulty);
        int hp = 20 + preset.constitution();
        int sp = 6 + Math.floorDiv(preset.intelligence(), 2);
        Talent talent = world == null ? null : world.talents().entrySet().stream().findFirst()
                .map(entry -> new Talent(entry.getKey(), entry.getValue().name(), entry.getValue().description(), false)).orElse(null);
        int armorBonus = switch (archetype) { case "swordsman" -> 1; case "mage", "ranger", "alchemist" -> 0; default -> 1; };
        return new ApState(request.characterName(), request.age(), request.gender(), archetype, difficulty,
                1, 0, 100, preset.strength(), preset.dexterity(), preset.constitution(), preset.intelligence(),
                preset.wisdom(), preset.charisma(), hp, hp, sp, sp,
                List.of(new Item(preset.weapon(), "weapon", preset.damage(), 1),
                        new Item(preset.armor(), "armor", "", 1, ApItemEffect.none(), 0, 0, 0, armorBonus)),
                new Equipment(preset.weapon(), preset.armor()),
                new Resources(diff.startingCopper(), diff.startingRations(), diff.startingPotions()), talent,
                List.of(), List.of(), List.of(), ApSystems.empty());
    }

    @Override public ActionValidation validate(ApState state, ApAction action) { return validate(state, action, null); }

    /** 检查类型化 AP 动作是否能在当前状态执行。
     * @param state 当前 AP 状态
     * @param action 待检查的动作
     * @param world 已确认世界素材，可为空
     */
    public ActionValidation validate(ApState state, ApAction action, WorldDefinition world) {
        if (state == null || action == null) return ActionValidation.reject("State and action are required");
        if (state.hpCurrent() <= 0 && !(action instanceof SettleBattle)) return ActionValidation.reject("Character is not alive");
        if (action instanceof SupplyEvent supply) return supply.rations() >= 0 ? ActionValidation.accept() : ActionValidation.reject("补给数量无效");
        if (action instanceof LearnAbility learn) {
            AbilityDefinition definition = learn.definition() != null ? learn.definition() : world == null ? null : world.abilities().get(learn.abilityId());
            if (definition == null) return ActionValidation.reject("Unknown ability");
            if (state.hasAbility(learn.abilityId())) return ActionValidation.reject("Ability already learned");
            for (var requirement : definition.prerequisites().entrySet())
                if (state.attribute(requirement.getKey()) < requirement.getValue()) return ActionValidation.reject("Prerequisite not met: " + requirement.getKey());
            return ActionValidation.accept();
        }
        if (action instanceof EquipItem equip) {
            return state.inventory().stream().anyMatch(item -> item.name().equals(equip.itemName()) && item.type().equals(equip.slot()))
                    ? ActionValidation.accept() : ActionValidation.reject("Item is not available for that equipment slot");
        }
        if (action instanceof UseItem use) {
            if (isPotion(use.itemName())) return state.resources().potions() > 0 && state.hpCurrent() < state.hpMax()
                    ? ActionValidation.accept() : ActionValidation.reject("Potion cannot be used");
            return state.inventory().stream().anyMatch(item -> item.name().equals(use.itemName()) && item.quantity() > 0 && item.effect().usable())
                    ? ActionValidation.accept() : ActionValidation.reject("Item is unavailable or has no usable effect");
        }
        if (action instanceof GrantExperience grant) return grant.amount() > 0 ? ActionValidation.accept() : ActionValidation.reject("Experience must be positive");
        if (action instanceof ConsumeRation) return state.resources().rations() > 0 ? ActionValidation.accept() : ActionValidation.reject("Not enough rations");
        if (action instanceof Rest || action instanceof AdvanceDay) {
            return state.battle() == null || !state.battle().active()
                    ? ActionValidation.accept() : ActionValidation.reject("战斗中不能休息或推进日期");
        }
        if (action instanceof OfferQuest offer) return offer.quest() != null && state.systems().questLog().stream().noneMatch(q -> q.questId().equals(offer.quest().questId())) ? ActionValidation.accept() : ActionValidation.reject("Quest already exists");
        if (action instanceof AcceptQuest accept) return quest(state, accept.questId(), ApQuest.Status.AVAILABLE) != null ? ActionValidation.accept() : ActionValidation.reject("Quest is not available");
        if (action instanceof ProgressQuest progress) {
            ApQuest current = quest(state, progress.questId(), ApQuest.Status.ACTIVE);
            return current != null && progress.amount() > 0 && progress.objectiveIndex() >= 0
                    && progress.objectiveIndex() < current.objectives().size()
                    ? ActionValidation.accept() : ActionValidation.reject("Quest progress is invalid");
        }
        if (action instanceof CompleteQuest complete) {
            ApQuest current = quest(state, complete.questId(), ApQuest.Status.ACTIVE);
            return current != null && current.objectives().stream().allMatch(ApQuest.Objective::complete)
                    ? ActionValidation.accept() : ActionValidation.reject("Quest is not active or objectives are incomplete");
        }
        if (action instanceof FailQuest fail) return quest(state, fail.questId(), ApQuest.Status.ACTIVE) != null ? ActionValidation.accept() : ActionValidation.reject("Quest is not active");
        if (action instanceof RegisterNpc npc) return npc.npcId() != null && !npc.npcId().isBlank() && npc.name() != null && !npc.name().isBlank()
                && (world == null || world.npcTypes().containsKey(npc.npcType()))
                && state.systems().npcs().stream().noneMatch(value -> value.npcId().equals(npc.npcId()))
                ? ActionValidation.accept() : ActionValidation.reject("NPC data is invalid or type is unknown");
        if (action instanceof InteractNpc interact) {
            ApNpc current = npc(state, interact.npcId());
            return current != null && current.status() == ApNpc.Status.ACTIVE
                    && interact.action() != null && !interact.action().isBlank()
                    ? ActionValidation.accept() : ActionValidation.reject("NPC 不可交谈或互动无效");
        }
        if (action instanceof RecordNpcLifeEvent lifeEvent) return npc(state, lifeEvent.npcId()) != null && lifeEvent.description() != null && !lifeEvent.description().isBlank() ? ActionValidation.accept() : ActionValidation.reject("NPC life event is invalid");
        if (action instanceof SetNpcStatus update) return npc(state, update.npcId()) != null && update.status() != null ? ActionValidation.accept() : ActionValidation.reject("NPC status update is invalid");
        if (action instanceof TickNpcs) return ActionValidation.accept();
        if (action instanceof AcquireItem acquire) return world != null && acquire.name() != null && !acquire.name().isBlank()
                && !isPotion(acquire.name())
                && world.itemTemplates().containsKey(acquire.category() + ":" + acquire.tier())
                ? ActionValidation.accept() : ActionValidation.reject("Unknown item template");
        if (action instanceof StartBattle start) {
            if (state.battle() != null && !state.battle().rewardsSettled()) {
                return ActionValidation.reject("当前无法发起战斗");
            }
            if (start.monsterId() == null || start.monsterId().isBlank()) {
                return ActionValidation.reject("缺少遭遇怪物素材标识");
            }
            if (world == null || !world.monsters().containsKey(start.monsterId())) {
                return ActionValidation.reject("找不到怪物素材：" + start.monsterId());
            }
            return ActionValidation.accept();
        }
        if (action instanceof BattleAction battle) {
            try {
                prepareBattleAction(state, battle, true);
                return ActionValidation.accept();
            } catch (IllegalArgumentException exception) {
                return ActionValidation.reject(exception.getMessage());
            }
        }
        if (action instanceof AdvanceBattleTurn) return state.systems().battle() != null && state.systems().battle().active() ? ActionValidation.accept() : ActionValidation.reject("No active battle");
        if (action instanceof SettleBattle settle) return state.systems().battle() != null && !state.systems().battle().rewardsSettled()
                && state.systems().battle().result() != ApBattle.Result.ACTIVE
                && state.systems().battle().result() == settle.result()
                ? ActionValidation.accept() : ActionValidation.reject("Battle result is not ready to settle");
        return ActionValidation.reject("Unsupported AP action");
    }

    /** 准备战斗动作，一次性检查行动者、目标和模板资源消耗。
     * @param state 当前 AP 状态
     * @param action 待执行的类型化战斗动作
     * @param playerAction 是否由玩家发起；否则为引擎执行的敌方攻击
     */
    private PreparedBattleAction prepareBattleAction(ApState state, BattleAction action, boolean playerAction) {
        ApBattle.Snapshot battle = state.battle();
        if (battle == null || !battle.active()) throw new IllegalArgumentException("没有进行中的战斗");
        ApBattle.Combatant actor = battle.currentActor();
        if (!actor.alive() || actor.player() != playerAction) throw new IllegalArgumentException("当前不是该行动者的回合");
        ApBattle.ActionKind kind = action.action();
        if (kind == null) throw new IllegalArgumentException("缺少战斗动作");
        Ability ability = kind == ApBattle.ActionKind.SKILL || kind == ApBattle.ActionKind.SPELL
                ? combatAbility(state, actor, action) : null;
        int cost = switch (kind) {
            case ATTACK, DEFEND, DODGE -> BASIC_ATTACK_COST;
            case HEAVY -> 3;
            case ITEM, FOCUS -> 1;
            case WAIT -> 0;
            case SPELL, SKILL -> Objects.requireNonNull(ability).apCost();
        };
        if (actor.currentAp() < cost) throw new IllegalArgumentException("行动点不足");
        if (ability != null && actor.sp() < ability.spCost()) throw new IllegalArgumentException("法力不足");

        ApBattle.Combatant target = null;
        boolean needsOpponent = kind == ApBattle.ActionKind.ATTACK || kind == ApBattle.ActionKind.HEAVY
                || (ability != null && switch (ability.mechanic().category()) {
                    case DAMAGE, ATTACK_MODIFIER, DAMAGE_DEBUFF, DOT, DEBUFF -> true;
                    default -> false;
                });
        if (needsOpponent) {
            target = target(battle, action.targetId());
            if (target == null || target.player() == actor.player()) {
                throw new IllegalArgumentException("无效的战斗目标：" + action.targetId());
            }
        } else if (ability != null && action.targetId() != null && !action.targetId().isBlank()
                && !action.targetId().equals(actor.id())) {
            throw new IllegalArgumentException("该能力只能以自身为目标");
        }
        if (kind == ApBattle.ActionKind.ITEM && !canUseCombatItem(state, actor, action.itemName())) {
            throw new IllegalArgumentException("战斗中没有可用的指定道具");
        }
        return new PreparedBattleAction(actor, target, ability, cost);
    }

    /** 检查角色是否有可用于本回合的道具。
     * @param state 当前 AP 状态
     * @param actor 当前行动者
     * @param name 背包道具名称或药水别名
     */
    private static boolean canUseCombatItem(ApState state, ApBattle.Combatant actor, String name) {
        if (!actor.player()) return false;
        if (isPotion(name)) return state.resources().potions() > 0 && actor.hp() < actor.maxHp();
        return state.inventory().stream().anyMatch(item -> item.name().equals(name)
                && item.quantity() > 0 && item.effect().usable());
    }

    /** 以已学实例标识确定能力，并按实例绑定的模板读取本次计算所需的参数。
     * @param state 当前 AP 角色状态
     * @param actor 本次行动者
     * @param action 指定技能实例及其施放类型的动作
     */
    private Ability combatAbility(ApState state, ApBattle.Combatant actor, BattleAction action) {
        Ability learned = findAbility(state, action.abilityId());
        if (learned == null) throw new IllegalArgumentException("未学习能力：" + action.abilityId());
        String expected = action.action() == ApBattle.ActionKind.SKILL ? "skill" : "spell";
        if (!expected.equals(learned.kind())) throw new IllegalArgumentException("施放动作与已学能力类别不符");
        Ability ability = learned;
        String templateId = learned.templateId();
        if (rules.isObject() || templateId != null) {
            if (templateId == null || templateId.isBlank()) throw new IllegalArgumentException("已学能力缺少 AP 模板绑定");
            AbilityDefinition definition = ApTemplateContent.ability(learned.id(), learned.name(), learned.description(),
                    rules.path(templateId), templateId);
            if (!expected.equals(definition.kind())) throw new IllegalArgumentException("绑定模板的能力类别已不匹配");
            ability = learnedAbility(definition, templateId);
        }
        if (!"active".equals(ability.mechanic().activation())) throw new IllegalArgumentException("被动能力不可主动施放");
        if (ability.mechanic().oncePerCombat() && actor.usedAbilities().contains(ability.id())) {
            throw new IllegalArgumentException("该能力在当前战斗已使用");
        }
        return ability;
    }

    /** 保存一次战斗动作校验后得到的结算参数。
     * @param actor 当前行动者
     * @param target 已确认的敌方目标，自身效果或无目标时为空
     * @param ability 按已学实例解析的能力，普通动作时为空
     * @param apCost 行动点消耗
     */
    private record PreparedBattleAction(ApBattle.Combatant actor, ApBattle.Combatant target, Ability ability, int apCost) {}

    @Override public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action) { return resolve(state, action, 0, null); }
    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action, int hpRecoveryBonus) { return resolve(state, action, hpRecoveryBonus, null); }

    /** 校验并执行 AP 动作，返回新状态和本轮实际产生的事件。
     * @param state 当前 AP 状态
     * @param action 待执行的 AP 动作
     * @param hpRecoveryBonus 难度带来的额外日恢复生命点数
     * @param world 已确认的世界素材，可为空
     */
    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action, int hpRecoveryBonus, WorldDefinition world) {
        if (state != null && action instanceof BattleAction battle) {
            return battleAction(state, battle, prepareBattleAction(state, battle, true));
        }
        ActionValidation validation = validate(state, action, world);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        if (action instanceof SupplyEvent supply) {
            return result(state.withResources(state.resources().add(0, supply.rations(), 0)),
                    new ApEvent("supply_event", supply.rations(), supply.name() + "带来 " + supply.rations() + " 份口粮。",
                            supply.id(), "player"));
        }
        if (action instanceof LearnAbility learn) {
            AbilityDefinition definition = learn.definition() != null ? learn.definition() : Objects.requireNonNull(world).abilities().get(learn.abilityId());
            Ability ability = learnedAbility(definition, learn.templateId());
            ApState next = "spell".equals(definition.kind()) ? state.withSpellsAdd(ability) : state.withSkillsAdd(ability);
            return result(next, new ApEvent("learn_ability", 1, "学会了「" + ability.name() + "」。", "player", ability.id()));
        }
        if (action instanceof EquipItem equip) return result(state.withEquipment("weapon".equals(equip.slot())
                ? state.equipment().withWeapon(equip.itemName()) : state.equipment().withArmor(equip.itemName())),
                new ApEvent("equip_item", 1));
        if (action instanceof UseItem use) return useItem(state, use);
        if (action instanceof GrantExperience grant) {
            ApState next = applyExperience(state, grant.amount());
            return result(next, new ApEvent("experience", grant.amount(), next.level() - state.level()));
        }
        if (action instanceof Rest) {
            int hp = Math.min(2, state.hpMax() - state.hpCurrent());
            int sp = Math.min(2, state.spMax() - state.spCurrent());
            return result(state.withVitality(state.hpCurrent() + hp, state.spCurrent() + sp),
                    new ApEvent("rest", hp, "休息恢复了 " + hp + " 点生命和 " + sp + " 点法力。", "player", "player"));
        }
        if (action instanceof ConsumeRation) return result(state.withResources(state.resources().withRations(state.resources().rations() - 1)), new ApEvent("consume_ration", 1));
        if (action instanceof AdvanceDay) return advanceDay(state, hpRecoveryBonus);
        if (action instanceof OfferQuest offer) return result(state.withSystems(state.systems().withQuest(offer.quest())), new ApEvent("quest_offered", 1));
        if (action instanceof AcceptQuest accept) return questTransition(state, accept.questId(), q -> q.accept(), "quest_accepted");
        if (action instanceof ProgressQuest progress) return questTransition(state, progress.questId(), q -> q.progress(progress.objectiveIndex(), progress.amount()), "quest_progress");
        if (action instanceof CompleteQuest complete) {
            ApQuest q = quest(state, complete.questId(), ApQuest.Status.ACTIVE);
            ApState next = state.withSystems(state.systems().replaceQuest(q.complete()));
            next = next.withResources(next.resources().add(q.reward().copper(), q.reward().rations(), q.reward().potions()));
            if (q.reward().experience() > 0) next = applyExperience(next, q.reward().experience());
            return result(next, new ApEvent("quest_completed", q.reward().experience(),
                    "完成「" + q.title() + "」，获得 " + q.reward().experience() + " 点经验、" + q.reward().copper()
                            + " 枚铜币、" + q.reward().rations() + " 份口粮和 " + q.reward().potions() + " 瓶药水。",
                    "player", q.questId()));
        }
        if (action instanceof FailQuest fail) return questTransition(state, fail.questId(), q -> q.fail(fail.reason()), "quest_failed");
        if (action instanceof RegisterNpc register) return result(state.withSystems(state.systems().withNpc(createNpc(register, world))),
                new ApEvent("npc_registered", 1, register.name() + "已登场。", register.npcId(), ""));
        if (action instanceof InteractNpc interact) {
            ApNpc current = npc(state, interact.npcId());
            var random = new java.util.SplittableRandom(interact.seed());
            int magnitude = random.nextInt(3, 9);
            int relation = interact.result() == ApNpc.InteractionResult.POSITIVE ? magnitude
                    : interact.result() == ApNpc.InteractionResult.NEGATIVE ? -magnitude : random.nextInt(0, 3);
            int mood = interact.result() == ApNpc.InteractionResult.POSITIVE ? random.nextInt(1, 4)
                    : interact.result() == ApNpc.InteractionResult.NEGATIVE ? -random.nextInt(1, 4) : 0;
            ApNpc updated = current.interact(interact.day(), interact.action(), interact.result(), relation, mood);
            int change = updated.relationship() - current.relationship();
            return result(state.withSystems(state.systems().replaceNpc(updated)), new ApEvent("npc_interaction", change,
                    "与「" + current.name() + "」进行了互动，关系变化 " + change + "。",
                    "player", current.npcId()));
        }
        if (action instanceof RecordNpcLifeEvent lifeEvent) {
            ApNpc current = npc(state, lifeEvent.npcId());
            return result(state.withSystems(state.systems().replaceNpc(current.withLifeEvent(new ApNpc.LifeEvent(lifeEvent.day(), lifeEvent.description())))), new ApEvent("npc_life_event", 1));
        }
        if (action instanceof SetNpcStatus update) {
            ApNpc current = npc(state, update.npcId());
            return result(state.withSystems(state.systems().replaceNpc(current.withStatus(update.status()))), new ApEvent("npc_status", 1));
        }
        if (action instanceof TickNpcs tick) {
            List<ApNpc> next = state.systems().npcs().stream().map(npc -> npc.dailyTick(tick.day(), tick.seed() ^ npc.npcId().hashCode())).toList();
            return result(state.withSystems(state.systems().withNpcs(next)), new ApEvent("npc_tick", next.size()));
        }
        if (action instanceof AcquireItem acquire) {
            Item item = createItem(world, acquire.name(), acquire.category(), acquire.tier(), new SplittableRandom(acquire.seed()));
            List<Item> inventory = new ArrayList<>(state.inventory());
            int index = -1;
            for (int i = 0; i < inventory.size(); i++) {
                if (inventory.get(i).name().equals(item.name())) { index = i; break; }
            }
            if (index >= 0) {
                Item old = inventory.get(index);
                if (!old.type().equals(item.type()) || !old.effect().equals(item.effect())
                        || old.weaponDamageBonus() != item.weaponDamageBonus()
                        || old.armorMitigationBonus() != item.armorMitigationBonus()) {
                    throw new IllegalArgumentException("同名道具已有不同的效果：" + item.name());
                }
                inventory.set(index, new Item(old.name(), old.type(), old.damage(), Math.addExact(old.quantity(), 1), old.effect(), old.price(), old.value(), old.weaponDamageBonus(), old.armorMitigationBonus()));
            } else inventory.add(item);
            return result(state.withInventory(inventory), new ApEvent("item_acquired", 1,
                    "获得「" + item.name() + "」。", "player", acquire.category()));
        }
        if (action instanceof StartBattle start) return startBattle(state, start, world);
        if (action instanceof AdvanceBattleTurn) return playEnemyTurns(advanceBattle(state));
        if (action instanceof SettleBattle settle) return settleBattle(state, settle.result());
        throw new IllegalArgumentException("Unsupported AP action");
    }

    /** 使用背包道具并报告实际恢复量。
     * @param state 结算前的 AP 状态
     * @param use 要使用的道具
     */
    private RulesetResolution<ApState, ApEvent> useItem(ApState state, UseItem use) {
        if (isPotion(use.itemName())) {
            int hp = Math.min(state.hpMax(), state.hpCurrent() + 10);
            return result(state.withVitality(hp, state.spCurrent()).withResources(state.resources().withPotions(state.resources().potions() - 1)),
                    itemUsed("药水", hp - state.hpCurrent(), 0));
        }
        Item used = usableItem(state, use.itemName());
        ApItemEffect effect = used.effect();
        int hp = Math.min(state.hpMax(), Math.addExact(state.hpCurrent(), effect.hpRecovery()));
        int sp = Math.min(state.spMax(), Math.addExact(state.spCurrent(), effect.spRecovery()));
        List<StatusEffect> statuses = effect.clearStatuses() ? new ArrayList<>() : new ArrayList<>(state.statusEffects());
        effect.statusApplications().forEach(status -> statuses.add(new StatusEffect(status.name(), status.duration(), status.penalty())));
        return result(state.withVitality(hp, sp).withStatusEffects(statuses).withInventory(consumeItem(state.inventory(), used)),
                itemUsed(used.name(), hp - state.hpCurrent(), sp - state.spCurrent()));
    }

    /** 创建使用道具后的确认事件。
     * @param name 已使用道具的名称
     * @param hp 实际恢复的生命点数
     * @param sp 实际恢复的法力点数
     */
    private static ApEvent itemUsed(String name, int hp, int sp) {
        return new ApEvent("use_item", hp, "使用「" + name + "」，恢复 " + hp + " 点生命和 " + sp + " 点法力。",
                "player", "player");
    }

    /** 取出满足使用条件的背包道具。
     * @param state 当前 AP 状态
     * @param name 背包中的道具名称
     */
    private static Item usableItem(ApState state, String name) {
        return state.inventory().stream().filter(item -> item.name().equals(name)
                && item.quantity() > 0 && item.effect().usable()).findFirst().orElseThrow();
    }

    private RulesetResolution<ApState, ApEvent> advanceDay(ApState state, int bonus) {
        state = state.withSystems(state.systems().advanceThreat(difficultyPresets().get(state.difficulty()).threatInterval(), THREAT_LEVELS.length - 1));
        int hpRecovery = Math.max(0, state.hpMax() / 4 + bonus);
        int spRecovery = state.spMax() / 2;
        ApState next = state.withVitality(Math.min(state.hpMax(), state.hpCurrent() + hpRecovery), Math.min(state.spMax(), state.spCurrent() + spRecovery)).withStatusEffects(tickStatusEffects(state.statusEffects()));
        if (next.resources().rations() > 0) next = next.withResources(next.resources().withRations(next.resources().rations() - 1));
        else next = next.withStatusEffect(new StatusEffect("饥饿", 1, "所有判定-2"));
        List<ApQuest> quests = next.systems().questLog().stream().map(ApQuest::tickDay).toList();
        next = next.withSystems(next.systems().withQuests(quests));
        return result(next, new ApEvent(next.resources().rations() >= state.resources().rations() ? "advance_day_hunger" : "advance_day", 1));
    }

    private RulesetResolution<ApState, ApEvent> questTransition(ApState state, String id, java.util.function.UnaryOperator<ApQuest> transition, String event) {
        ApQuest current = state.systems().questLog().stream().filter(q -> q.questId().equals(id)).findFirst().orElseThrow();
        return result(state.withSystems(state.systems().replaceQuest(transition.apply(current))), new ApEvent(event, 1));
    }

    /** 按指定怪物素材生成战斗，若敌方先动则自动结算其行动。
     * @param state 当前 AP 状态
     * @param start 已校验的遭遇名称和怪物素材标识
     * @param world 包含已确认怪物素材的世界定义
     */
    private RulesetResolution<ApState, ApEvent> startBattle(ApState state, StartBattle start, WorldDefinition world) {
        ApBattle.Combatant monster = monsterCombatant(world.monsters().get(start.monsterId()), state.difficulty());
        List<ApBattle.Combatant> combatants = List.of(playerCombatant(state), monster).stream()
                .sorted(Comparator.comparingInt(ApBattle.Combatant::speed).reversed().thenComparing(ApBattle.Combatant::id)).toList();
        List<String> order = combatants.stream().map(ApBattle.Combatant::id).toList();
        ApBattle.Snapshot battle = new ApBattle.Snapshot(1, 0, order, combatants, List.of(), true, ApBattle.Result.ACTIVE, start.encounterName(), false);
        return playEnemyTurns(result(state.withSystems(state.systems().withBattle(battle)),
                new ApEvent("battle_started", 1, "遭遇了「" + monster.name() + "」。", "player", monster.id())));
    }

    public Item createItem(WorldDefinition world, String name, String category, String tier,
                           RandomGenerator random) {
        if (world == null || random == null) throw new IllegalArgumentException("World and random source are required");
        var template = world.itemTemplates().get(category + ":" + tier);
        if (template == null) throw new IllegalArgumentException("Unknown item template");
        long value = template.valueRange().roll(random);
        ApItemEffect effect = new ApItemEffect(template.hpRecovery(), template.spRecovery(),
                template.clearsStatuses(), List.of(), 0, 0, 0);
        String type = switch (category) {
            case "weapon", "armor", "accessory" -> category;
            default -> "consumable";
        };
        return new Item(name, type, category.equals("weapon") ? "+" + template.weaponDamageBonus() : "",
                1, effect, value, value, template.weaponDamageBonus(), template.armorMitigationBonus());
    }

    private ApBattle.Combatant playerCombatant(ApState state) {
        int power = state.strength() + weaponDamage(state);
        int mitigation = Math.floorDiv(state.constitution(), 3) + armorMitigation(state);
        int maxAp = Math.min(12, 4 + state.dexterity() / 3 + state.level() / 3);
        return new ApBattle.Combatant("player", state.characterName(), true, "", state.hpCurrent(), state.hpMax(), state.spCurrent(), state.spMax(), power, mitigation, state.dexterity() + state.level(), maxAp, maxAp, 0, 0, false, false, List.of(), 0, 0, List.of(), Set.of());
    }

    /** 按模板和难度生成执行普通攻击的怪物战斗单位。
     * @param monster 已确认的怪物素材数值
     * @param difficulty AP 难度标识
     */
    private ApBattle.Combatant monsterCombatant(MonsterDefinition monster, String difficulty) {
        DifficultyDefinition preset = difficultyPresets().get(difficulty);
        int hp = Math.max(1, scale(monster.maxHp(), preset.enemyHpMultiplierPercent()));
        int power = scale(monster.attackPower(), preset.enemyPowerMultiplierPercent());
        int ap = Math.max(BASIC_ATTACK_COST, scale(4, preset.enemyApMultiplierPercent()));
        int xp = scale(10, preset.experienceMultiplierPercent());
        int copper = scale(1, preset.goldMultiplierPercent());
        return new ApBattle.Combatant("enemy-1", monster.name(), false, monster.id(), hp, hp, 0, 0,
                power, 0, 6, ap, ap, 0, 0, false, false, List.of(), xp, copper, List.of(), Set.of());
    }

    /** 应用已通过校验的战斗动作并记录实际效果。
     * @param state 当前 AP 状态
     * @param action 待执行的战斗动作
     * @param prepared 本次校验后的行动者、目标、模板能力与消耗
     */
    private RulesetResolution<ApState, ApEvent> battleAction(ApState state, BattleAction action, PreparedBattleAction prepared) {
        ApBattle.Snapshot battle = state.battle();
        ApBattle.Combatant actor = prepared.actor();
        ApBattle.Combatant target = prepared.target();
        Ability ability = prepared.ability();
        List<ApBattle.Combatant> values = new ArrayList<>(battle.combatants());
        int cost = prepared.apCost();
        int spCost = ability == null ? 0 : ability.spCost();
        int guardBefore = actor.guard();
        int powerUsed = actor.power();
        int damage = 0;
        int hpRestored = 0;
        int spRestored = 0;
        String outcome = "";
        String effect = "";
        if (action.action() == ApBattle.ActionKind.ATTACK || action.action() == ApBattle.ActionKind.HEAVY) {
            int power = action.action() == ApBattle.ActionKind.HEAVY ? scale(actor.power(), 150) : actor.power();
            powerUsed = power;
            DamageResult hit = damage(power, target);
            damage = Math.min(target.hp(), hit.damage()); outcome = hit.outcome();
            target = target.update(target.hp() - damage, target.sp(), target.power(), target.mitigation(), target.currentAp(), target.guard(), target.exposedRounds(), false, false, target.statuses(), target.usedAbilities());
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), action.action() == ApBattle.ActionKind.HEAVY ? 1 : actor.exposedRounds(), actor.defending(), actor.dodging(), actor.statuses(), actor.usedAbilities());
            values = replace(replace(values, actor), target);
        } else if (action.action() == ApBattle.ActionKind.DEFEND) {
            int bank = Math.min(2, actor.currentAp() - cost);
            cost += bank;
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard() + bank, actor.exposedRounds(), true, false, actor.statuses(), actor.usedAbilities()); values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.DODGE) {
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), false, true, actor.statuses(), actor.usedAbilities()); values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.FOCUS) {
            spRestored = Math.min(2, actor.maxSp() - actor.sp());
            actor = actor.update(actor.hp(), actor.sp() + spRestored, actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), actor.defending(), actor.dodging(), actor.statuses(), actor.usedAbilities());
            values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.ITEM) {
            if (isPotion(action.itemName())) {
                effect = "药水";
                hpRestored = Math.min(10, actor.maxHp() - actor.hp());
                actor = actor.update(actor.hp() + hpRestored, actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), actor.defending(), actor.dodging(), actor.statuses(), actor.usedAbilities());
                state = state.withResources(state.resources().withPotions(state.resources().potions() - 1));
            } else {
                Item used = usableItem(state, action.itemName());
                effect = used.name();
                ApItemEffect itemEffect = used.effect();
                hpRestored = Math.min(itemEffect.hpRecovery(), actor.maxHp() - actor.hp());
                spRestored = Math.min(itemEffect.spRecovery(), actor.maxSp() - actor.sp());
                List<ApBattle.CombatStatus> statuses = itemEffect.clearStatuses() ? List.of() : actor.statuses();
                actor = actor.update(actor.hp() + hpRestored, actor.sp() + spRestored, actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), actor.defending(), actor.dodging(), statuses, actor.usedAbilities());
                state = state.withInventory(consumeItem(state.inventory(), used));
            }
            values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.WAIT) {
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), 0, actor.guard() + Math.min(4, actor.currentAp()), actor.exposedRounds(), false, false, actor.statuses(), actor.usedAbilities()); values = replace(values, actor);
        } else if (ability != null) {
            AbilityMechanic mechanic = ability.mechanic();
            Set<String> usedAbilities = new HashSet<>(actor.usedAbilities());
            if (mechanic.oncePerCombat()) usedAbilities.add(ability.id());
            List<ApBattle.CombatStatus> statuses = new ArrayList<>(actor.statuses());
            int nextGuard = actor.guard();
            int nextHp = actor.hp();
            switch (mechanic.category()) {
                case DAMAGE, ATTACK_MODIFIER, DAMAGE_DEBUFF, DOT -> {
                    int basePower = ability.templateId() != null || ability.power() > 0 ? ability.power()
                            : mechanic.power() > 0 ? mechanic.power() : actor.power();
                    int power = scale(basePower, mechanic.powerMultiplierPercent());
                    if (mechanic.category() == AbilityMechanic.Category.ATTACK_MODIFIER && mechanic.powerMultiplierPercent() == 100) power = scale(actor.power(), 150);
                    powerUsed = power;
                    DamageResult hit = damage(power, target);
                    damage = Math.min(target.hp(), hit.damage()); outcome = hit.outcome();
                    target = target.update(target.hp() - damage, target.sp(), target.power(), target.mitigation(), target.currentAp(), target.guard(), target.exposedRounds(), false, false, target.statuses(), target.usedAbilities());
                    if (mechanic.category() == AbilityMechanic.Category.DAMAGE_DEBUFF || mechanic.category() == AbilityMechanic.Category.DOT) {
                        String statusEffect = mechanic.effect().isBlank() ? "hp_drain" : mechanic.effect();
                        List<ApBattle.CombatStatus> targetStatuses = new ArrayList<>(target.statuses());
                        targetStatuses.add(new ApBattle.CombatStatus(ability.name(), statusEffect, mechanic.amount(), Math.max(1, mechanic.duration())));
                        target = target.update(target.hp(), target.sp(), target.power(), target.mitigation(), target.currentAp(), target.guard(), target.exposedRounds(), target.defending(), target.dodging(), targetStatuses, target.usedAbilities());
                    }
                    values = replace(values, target);
                    if (mechanic.activation().equals("active") && mechanic.category() == AbilityMechanic.Category.ATTACK_MODIFIER) actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp(), actor.guard(), 1, actor.defending(), actor.dodging(), statuses, usedAbilities);
                }
                case HEAL, SELF_HEAL -> {
                    hpRestored = Math.min(mechanic.healAmount(), actor.maxHp() - actor.hp());
                    nextHp += hpRestored;
                    if (mechanic.clearStatuses()) statuses.clear();
                }
                case DEBUFF -> {
                    List<ApBattle.CombatStatus> targetStatuses = new ArrayList<>(target.statuses());
                    String statusEffect = mechanic.effect().isBlank() ? "exposed" : mechanic.effect();
                    if (statusEffect.endsWith("_drain") && statusEffect.startsWith("mitigation")) {
                        int applied = Math.min(target.mitigation(), mechanic.amount());
                        target = target.update(target.hp(), target.sp(), target.power(), target.mitigation() - applied, target.currentAp(), target.guard(), target.exposedRounds(), target.defending(), target.dodging(), targetStatuses, target.usedAbilities());
                        statusEffect = "mitigation_restore";
                        targetStatuses.add(new ApBattle.CombatStatus(ability.name(), statusEffect, applied, Math.max(1, mechanic.duration())));
                    } else targetStatuses.add(new ApBattle.CombatStatus(ability.name(), statusEffect, mechanic.amount(), Math.max(1, mechanic.duration())));
                    target = target.update(target.hp(), target.sp(), target.power(), target.mitigation(), target.currentAp(), target.guard(), target.exposedRounds(), target.defending(), target.dodging(), targetStatuses, target.usedAbilities());
                    values = replace(values, target);
                }
                case SELF_BUFF -> {
                    if ("guard".equals(mechanic.buffStat())) nextGuard += mechanic.buffAmount();
                    statuses.add(new ApBattle.CombatStatus(ability.name(), "barrier", mechanic.buffAmount(), Math.max(1, mechanic.duration())));
                }
                default -> { }
            }
            actor = actor.update(nextHp, actor.sp() - spCost, actor.power(), actor.mitigation(), actor.currentAp() - cost, nextGuard,
                    actor.exposedRounds(), actor.defending(), actor.dodging(), statuses, usedAbilities);
            values = replace(values, actor);
            effect = ability.name();
            if (outcome.isBlank()) outcome = "ABILITY";
        }
        String targetId = target == null ? actor.id() : target.id();
        ApBattle.BattleEvent event = new ApBattle.BattleEvent(battle.round(), actor.id(), action.action(), targetId,
                cost, spCost, powerUsed, damage, hpRestored, outcome, effect);
        List<ApBattle.BattleEvent> log = new ArrayList<>(battle.log());
        log.add(event);
        ApBattle.Result result = battleResult(values);
        ApBattle.Snapshot nextBattle = battle.with(battle.round(), battle.turnIndex(), values, log, result == ApBattle.Result.ACTIVE, result, false);
        ApState next = state.withVitality(playerHp(values, state.hpCurrent()), playerSp(values, state.spCurrent()))
                .withSystems(state.systems().withBattle(nextBattle));
        int guard = actor.guard() - guardBefore;
        String actionName = switch (action.action()) {
            case ATTACK -> "普通攻击";
            case DEFEND -> "防御";
            case SKILL, SPELL, ITEM -> effect;
            default -> action.action().name().toLowerCase(Locale.ROOT);
        };
        String description = actor.name() + "使用「" + actionName + "」，消耗 " + cost + " 点行动力、" + spCost + " 点法力。";
        if (target != null && !outcome.isBlank()) description += "对「" + target.name() + "」造成 " + damage + " 点伤害。";
        if (hpRestored > 0) description += "恢复 " + hpRestored + " 点生命。";
        if (spRestored > 0) description += "恢复 " + spRestored + " 点法力。";
        if (guard > 0) description += "获得 " + guard + " 点护盾。";
        if (result == ApBattle.Result.VICTORY) description += "敌方已被击败。";
        if (result == ApBattle.Result.DEFEAT) description += "玩家已被击败。";
        long amount = target != null ? damage : Math.max(hpRestored + spRestored, guard);
        return result(next, new ApEvent("battle_action", amount, description, actor.id(), targetId));
    }

    /** 让当前敌方用模板威力执行一次普通攻击，直到轮到玩家或战斗结束。
     * @param initial 进入新回合或刚开战后的权威状态与确认事件
     */
    private RulesetResolution<ApState, ApEvent> playEnemyTurns(RulesetResolution<ApState, ApEvent> initial) {
        ApState state = initial.nextState();
        List<ApEvent> events = new ArrayList<>(initial.events());
        while (state.battle().active() && !state.battle().currentActor().player()) {
            if (state.battle().currentActor().currentAp() >= BASIC_ATTACK_COST) {
                BattleAction attack = new BattleAction(ApBattle.ActionKind.ATTACK, "player", "", "");
                RulesetResolution<ApState, ApEvent> hit = battleAction(state, attack, prepareBattleAction(state, attack, false));
                state = hit.nextState();
                events.addAll(hit.events());
            }
            if (state.battle().active()) {
                RulesetResolution<ApState, ApEvent> turn = advanceBattle(state);
                state = turn.nextState();
                events.addAll(turn.events());
            }
        }
        return new RulesetResolution<>(state, events);
    }

    /** 推进到下一个存活单位，刷新其行动点并更新战斗结果。
     * @param state 当前 AP 状态
     */
    private RulesetResolution<ApState, ApEvent> advanceBattle(ApState state) {
        ApBattle.Snapshot battle = state.systems().battle();
        int next = battle.turnIndex(); int round = battle.round();
        for (int i = 0; i < battle.turnOrder().size(); i++) { next = (next + 1) % battle.turnOrder().size(); if (battle.combatant(battle.turnOrder().get(next)).alive()) break; }
        boolean newRound = next <= battle.turnIndex();
        int selected = next;
        if (newRound) round++;
        List<ApBattle.Combatant> refreshed = new ArrayList<>();
        for (ApBattle.Combatant combatant : battle.combatants()) refreshed.add(tickCombatant(combatant, newRound, combatant.id().equals(battle.turnOrder().get(selected))));
        ApBattle.Result outcome = battleResult(refreshed);
        boolean active = outcome == ApBattle.Result.ACTIVE;
        String nextId = battle.turnOrder().get(next);
        if (active && refreshed.stream().noneMatch(unit -> unit.id().equals(nextId) && unit.alive())) {
            while (true) {
                next = (next + 1) % battle.turnOrder().size();
                String candidate = battle.turnOrder().get(next);
                ApBattle.Combatant alive = refreshed.stream().filter(unit -> unit.id().equals(candidate) && unit.alive())
                        .findFirst().orElse(null);
                if (alive == null) continue;
                refreshed = replace(refreshed, tickCombatant(alive, false, true));
                break;
            }
        }
        ApBattle.Snapshot snapshot = battle.with(round, next, refreshed, battle.log(), active, outcome, false);
        ApState updated = state.withVitality(playerHp(refreshed, state.hpCurrent()), playerSp(refreshed, state.spCurrent()))
                .withSystems(state.systems().withBattle(snapshot));
        if (!active) {
            return result(updated, new ApEvent(outcome == ApBattle.Result.VICTORY ? "battle_victory" : "battle_defeat", 0,
                    outcome == ApBattle.Result.VICTORY ? "敌方已被击败。" : "玩家已被击败。", "", ""));
        }
        ApBattle.Combatant actor = snapshot.currentActor();
        return result(updated, new ApEvent("battle_turn", round, "第 " + round + " 轮，轮到「" + actor.name() + "」行动。",
                actor.id(), ""));
    }

    /** 确认战斗结束并结算一次战利奖励。
     * @param state 当前 AP 状态
     * @param outcome 引擎已确认的战斗结果
     */
    private RulesetResolution<ApState, ApEvent> settleBattle(ApState state, ApBattle.Result outcome) {
        ApBattle.Snapshot battle = state.battle();
        ApBattle.Snapshot settled = battle.with(battle.round(), battle.turnIndex(), battle.combatants(), battle.log(),
                false, outcome, true);
        ApState next = state.withSystems(state.systems().withBattle(settled));
        if (outcome == ApBattle.Result.VICTORY) {
            long xp = battle.combatants().stream().filter(c -> !c.player()).mapToLong(ApBattle.Combatant::experienceReward).sum();
            long copper = battle.combatants().stream().filter(c -> !c.player()).mapToLong(ApBattle.Combatant::copperReward).sum();
            next = applyExperience(next.withResources(next.resources().add(copper, 0, 0)), xp);
            List<Item> inventory = new ArrayList<>(next.inventory());
            for (String loot : battle.combatants().stream().filter(c -> !c.player()).flatMap(c -> c.lootItems().stream()).toList()) {
                inventory.add(new Item(loot, "loot", "", 1));
            }
            next = next.withInventory(inventory);
            return result(next, new ApEvent("battle_settled", xp, "战斗获胜，获得 " + xp + " 点经验和 " + copper + " 枚铜币。",
                    "player", ""));
        }
        return result(next, new ApEvent("battle_settled", 0, "战斗已结束。", "player", ""));
    }

    private ApBattle.Result battleResult(List<ApBattle.Combatant> combatants) {
        boolean playerAlive = combatants.stream().anyMatch(c -> c.player() && c.alive());
        boolean enemiesAlive = combatants.stream().anyMatch(c -> !c.player() && c.alive());
        return !playerAlive ? ApBattle.Result.DEFEAT : !enemiesAlive ? ApBattle.Result.VICTORY : ApBattle.Result.ACTIVE;
    }

    /** 根据攻击威力和目标防御状态计算伤害。
     * @param power 本次实际攻击威力
     * @param target 受到攻击的存活战斗单位
     */
    private DamageResult damage(int power, ApBattle.Combatant target) {
        if (target.dodging() && target.speed() >= power) return new DamageResult(0, "DODGE");
        int mitigation = target.mitigation() + target.guard() + (target.defending() ? 2 : 0);
        int effectivePower = target.exposedRounds() > 0 ? scale(power, 150) : power;
        int value = Math.max(1, effectivePower - mitigation);
        if (mitigation > 0 && effectivePower > mitigation * 3) return new DamageResult(Math.multiplyExact(value, 2), "OVERWHELM");
        return new DamageResult(value, "HIT");
    }

    private ApBattle.Combatant tickCombatant(ApBattle.Combatant combatant, boolean newRound, boolean active) {
        int hp = combatant.hp();
        int ap = active ? combatant.maxAp() : combatant.currentAp();
        int mitigation = combatant.mitigation();
        int exposed = Math.max(0, combatant.exposedRounds() - (newRound ? 1 : 0));
        List<ApBattle.CombatStatus> statuses = new ArrayList<>();
        for (ApBattle.CombatStatus status : combatant.statuses()) {
            int nextDuration = status.duration() > 0 && newRound ? status.duration() - 1 : status.duration();
            if (newRound && ("hp_drain".equals(status.effect()) || "poison".equals(status.effect()))) hp = Math.max(0, hp - status.amount());
            if (newRound && "ap_drain".equals(status.effect()) && active) ap = Math.max(0, ap - status.amount());
            if (newRound && "mitigation_restore".equals(status.effect()) && nextDuration <= 0) mitigation += status.amount();
            if (nextDuration != 0) statuses.add(new ApBattle.CombatStatus(status.name(), status.effect(), status.amount(), nextDuration));
        }
        return combatant.update(hp, combatant.sp(), combatant.power(), mitigation, ap,
                newRound && combatant.guard() > 0 ? Math.max(0, combatant.guard() - 1) : combatant.guard(), exposed,
                newRound ? false : combatant.defending(), newRound ? false : combatant.dodging(), statuses, combatant.usedAbilities());
    }

    private int playerHp(List<ApBattle.Combatant> combatants, int fallback) { return combatants.stream().filter(ApBattle.Combatant::player).mapToInt(ApBattle.Combatant::hp).findFirst().orElse(fallback); }
    private int playerSp(List<ApBattle.Combatant> combatants, int fallback) { return combatants.stream().filter(ApBattle.Combatant::player).mapToInt(ApBattle.Combatant::sp).findFirst().orElse(fallback); }
    private record DamageResult(int damage, String outcome) {}

    private ApBattle.Combatant target(ApBattle.Snapshot battle, String id) { return id == null ? null : battle.combatants().stream().filter(c -> c.id().equals(id) && c.alive()).findFirst().orElse(null); }
    private List<ApBattle.Combatant> replace(List<ApBattle.Combatant> values, ApBattle.Combatant replacement) { return values.stream().map(c -> c.id().equals(replacement.id()) ? replacement : c).toList(); }
    private Ability findAbility(ApState state, String id) { return state.skills().stream().filter(a -> a.id().equals(id)).findFirst().orElseGet(() -> state.spells().stream().filter(a -> a.id().equals(id)).findFirst().orElse(null)); }

    /** 组装持久化能力以及结算使用的能力视图。
     * @param definition AP 解码后的能力定义
     * @param templateId 绑定的登记规则标识，底层独立定义可为空
     */
    private static Ability learnedAbility(AbilityDefinition definition, String templateId) {
        return new Ability(definition.id(), definition.kind(), definition.tier(), definition.apCost(), definition.spCost(),
                definition.power(), definition.name(), definition.effectHint(), definition.description(), definition.mechanic(), templateId);
    }
    private ApNpc createNpc(RegisterNpc input, WorldDefinition world) {
        var template = world == null ? null : world.npcTypes().get(input.npcType());
        String personality = template == null || template.personalities().isEmpty() ? "" : template.personalities().get(new java.util.SplittableRandom(input.seed()).nextInt(template.personalities().size()));
        String scheduleTemplate = template == null ? "" : template.scheduleTemplate();
        List<ApNpc.ScheduleEntry> schedule = scheduleTemplate.isBlank() ? List.of()
                : List.of(new ApNpc.ScheduleEntry("all_day", input.location(), scheduleTemplate));
        return new ApNpc(input.npcId(), input.name(), input.npcType(), personality, input.location(),
                template == null ? 0 : template.relationshipBase(), 0, ApNpc.Status.ACTIVE, schedule,
                List.of(), List.of(), template != null && template.canTrain(), template != null && template.canCombat(),
                template != null && template.sellsItems(), template != null && template.givesQuests(), scheduleTemplate);
    }
    private ApQuest quest(ApState state, String id, ApQuest.Status status) { return state.systems().questLog().stream().filter(q -> q.questId().equals(id) && q.status() == status).findFirst().orElse(null); }
    private ApNpc npc(ApState state, String id) { return state.systems().npcs().stream().filter(n -> n.npcId().equals(id)).findFirst().orElse(null); }
    private int weaponDamage(ApState state) { return state.inventory().stream().filter(i -> i.name().equals(state.equipment().weapon())).mapToInt(i -> i.weaponDamageBonus() > 0 ? i.weaponDamageBonus() : parseDamage(i.damage(), 3)).findFirst().orElse(3); }
    private int armorMitigation(ApState state) { return state.inventory().stream().filter(i -> i.name().equals(state.equipment().armor())).mapToInt(i -> i.armorMitigationBonus()).findFirst().orElse(state.archetype().equals("swordsman") || "custom".equals(state.archetype()) ? 1 : 0); }
    private int parseDamage(String raw, int fallback) { try { return Integer.parseInt(raw == null ? "" : raw.replace("+", "").trim()); } catch (NumberFormatException exception) { return fallback; } }
    private List<Item> consumeItem(List<Item> inventory, Item used) { var next = new ArrayList<>(inventory); int index = next.indexOf(used); if (used.quantity() <= 1) next.remove(index); else next.set(index, new Item(used.name(), used.type(), used.damage(), used.quantity() - 1, used.effect(), used.price(), used.value(), used.weaponDamageBonus(), used.armorMitigationBonus())); return List.copyOf(next); }
    private int scale(int value, int percent) { return Math.floorDiv(Math.multiplyExact(value, percent), 100); }
    private ApState applyExperience(ApState state, long amount) { long xp = Math.addExact(state.xp(), amount); int level = state.level(); long need = state.xpToNext(); while (xp >= need) { xp -= need; level = Math.addExact(level, 1); need = Math.multiplyExact(level, 100L); } return state.withProgression(level, xp, need); }
    private List<StatusEffect> tickStatusEffects(List<StatusEffect> effects) { return effects.stream().filter(e -> e.duration() > 1).map(e -> new StatusEffect(e.name(), e.duration() - 1, e.penalty())).toList(); }
    private static boolean isPotion(String name) { return "potion".equalsIgnoreCase(name) || "药水".equals(name); }
    private RulesetResolution<ApState, ApEvent> result(ApState state, ApEvent event) { return new RulesetResolution<>(state, List.of(event)); }
    @Override public ApProjection project(ApState state) { return new ApProjection(state); }

    private record Archetype(int strength, int dexterity, int intelligence, int constitution, int wisdom, int charisma, String weapon, String damage, String armor) {}
    public record ApInitializationRequest(String characterName, int age, String gender, String archetype, String difficulty) {}

    public record ApState(String characterName, int age, String gender, String archetype, String difficulty, int level, long xp, long xpToNext, int strength, int dexterity, int constitution, int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent, int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent, List<StatusEffect> statusEffects, List<Ability> skills, List<Ability> spells, ApSystems systems) implements GameState {
        public ApState { inventory = inventory == null ? List.of() : List.copyOf(inventory); statusEffects = statusEffects == null ? List.of() : List.copyOf(statusEffects); skills = skills == null ? List.of() : List.copyOf(skills); spells = spells == null ? List.of() : List.copyOf(spells); systems = systems == null ? ApSystems.empty() : systems; }
        public ApState(String characterName, int age, String gender, String archetype, String difficulty, int level, long xp, long xpToNext, int strength, int dexterity, int constitution, int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent, int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent) { this(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, List.of(), List.of(), List.of(), ApSystems.empty()); }
        public ApState(String characterName, int age, String gender, String archetype, String difficulty, int level, long xp, long xpToNext, int strength, int dexterity, int constitution, int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent, int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent, List<StatusEffect> statusEffects) { this(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, List.of(), List.of(), ApSystems.empty()); }
        /** 应用天赋的初始生命上限加成。
         * @param bonus 非负生命点数
         */
        public ApState withStartingHealthBonus(int bonus) {
            return copy(hpCurrent + bonus, hpMax + bonus, spCurrent, spMax, inventory, equipment, resources,
                    uniqueTalent, statusEffects, skills, spells, systems);
        }
        /** 返回当前难度对应的威胁升级间隔，单位为游戏日。 */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public long getThreatInterval() { return difficultyPresets().get(difficulty).threatInterval(); }

        public ApState withVitality(int hp, int sp) { return copy(hp, hpMax, sp, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, systems); }
        public ApState withResources(Resources value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, value, uniqueTalent, statusEffects, skills, spells, systems); }
        public ApState withStatusEffect(StatusEffect effect) { var next = new ArrayList<>(statusEffects); next.add(effect); return withStatusEffects(next); }
        public ApState withStatusEffects(List<StatusEffect> value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, value, skills, spells, systems); }
        public ApState withProgression(int value, long nextXp, long nextNeed) { return new ApState(characterName, age, gender, archetype, difficulty, value, nextXp, nextNeed, strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, systems); }
        public ApState withSkillsAdd(Ability ability) { var next = new ArrayList<>(skills); next.add(ability); return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, next, spells, systems); }
        public ApState withSpellsAdd(Ability ability) { var next = new ArrayList<>(spells); next.add(ability); return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, next, systems); }
        public ApState withEquipment(Equipment value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, value, resources, uniqueTalent, statusEffects, skills, spells, systems); }
        public ApState withInventory(List<Item> value) { return copy(hpCurrent, hpMax, spCurrent, spMax, value, equipment, resources, uniqueTalent, statusEffects, skills, spells, systems); }
        public ApState withSystems(ApSystems value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, value); }
        public List<ApQuest> questLog() { return systems.questLog(); }
        public List<ApNpc> npcs() { return systems.npcs(); }
        public ApBattle.Snapshot battle() { return systems.battle(); }
        private ApState copy(int hp, int maxHp, int sp, int maxSp, List<Item> items, Equipment gear, Resources values, Talent talent, List<StatusEffect> effects, List<Ability> learnedSkills, List<Ability> learnedSpells, ApSystems stateSystems) { return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity, constitution, intelligence, wisdom, charisma, hp, maxHp, sp, maxSp, items, gear, values, talent, effects, learnedSkills, learnedSpells, stateSystems); }
        public boolean hasAbility(String id) { return skills.stream().anyMatch(a -> a.id().equals(id)) || spells.stream().anyMatch(a -> a.id().equals(id)); }
        public long attribute(String name) { return switch (name) { case "STR" -> strength; case "DEX" -> dexterity; case "CON" -> constitution; case "INT" -> intelligence; case "WIS" -> wisdom; case "CHA" -> charisma; case "level" -> level; default -> 0; }; }
    }

    public record Item(String name, String type, String damage, int quantity, ApItemEffect effect,
                       long price, long value, int weaponDamageBonus, int armorMitigationBonus) {
        public Item { if (name == null || name.isBlank() || quantity < 0 || price < 0 || value < 0 || weaponDamageBonus < 0 || armorMitigationBonus < 0) throw new IllegalArgumentException("Invalid item"); effect = effect == null ? ApItemEffect.none() : effect; }
        public Item(String name, String type, String damage, int quantity) { this(name, type, damage, quantity, ApItemEffect.none(), 0, 0, 0, 0); }
        public Item(String name, String type, String damage, int quantity, ApItemEffect effect, long price, long value) { this(name, type, damage, quantity, effect, price, value, 0, 0); }
    }
    public record Equipment(String weapon, String armor) { public Equipment withWeapon(String value) { return new Equipment(value, armor); } public Equipment withArmor(String value) { return new Equipment(weapon, value); } }
    public record Resources(long copper, long rations, long potions) { public Resources { if (copper < 0 || rations < 0 || potions < 0) throw new IllegalArgumentException("Resources must not be negative"); } public Resources withRations(long value) { return new Resources(copper, value, potions); } public Resources withPotions(long value) { return new Resources(copper, rations, value); } public Resources add(long c, long r, long p) { return new Resources(Math.addExact(copper, c), Math.addExact(rations, r), Math.addExact(potions, p)); } }
    public record Talent(String type, String name, String description, boolean awakened) {}
    public record StatusEffect(String name, int duration, String penalty) { public StatusEffect { if (duration <= 0) throw new IllegalArgumentException("Status duration must be positive"); } }
    /** 保存角色已经学会的能力，以及结算时要查找的规则。
     * @param id 游戏内能力实例标识，施放时使用此标识
     * @param kind 能力类别，skill 或 spell
     * @param tier 能力层级
     * @param apCost 展示用的行动点消耗
     * @param spCost 展示用的法力消耗
     * @param power 展示用的威力
     * @param name 能力显示名称
     * @param effectHint 效果提示
     * @param description 能力的叙事描述
     * @param mechanic 已解码的效果视图
     * @param templateId 已登记规则标识，底层独立定义可为空
     */
    public record Ability(String id, String kind, int tier, int apCost, int spCost, int power, String name,
                          String effectHint, String description, AbilityMechanic mechanic, String templateId) {}

    /** AP 入口从结构化意图解析出的类型化动作。 */
    public sealed interface ApAction permits SupplyEvent, Rest, ConsumeRation, AdvanceDay, GrantExperience, LearnAbility, EquipItem, UseItem, OfferQuest, AcceptQuest, ProgressQuest, CompleteQuest, FailQuest, RegisterNpc, InteractNpc, TickNpcs, RecordNpcLifeEvent, SetNpcStatus, AcquireItem, StartBattle, BattleAction, AdvanceBattleTurn, SettleBattle { String type(); }
    /** 表示一次由已登记事件发放的补给。
     * @param id 世界补给事件素材标识
     * @param name 用于叙事的事件名称
     * @param rations 该事件发放的口粮份数
     */
    public record SupplyEvent(String id, String name, int rations) implements ApAction {
        /** 返回补给事件类型。 */
        public String type() { return "supply_event"; }
    }
    public record Rest() implements ApAction { public String type() { return "rest"; } }
    public record ConsumeRation() implements ApAction { public String type() { return "consume_ration"; } }
    public record AdvanceDay() implements ApAction { public String type() { return "advance_day"; } }
    public record GrantExperience(long amount) implements ApAction { public String type() { return "grant_experience"; } }
    /** 表示一次学习已登记能力或 AI 创作的能力。
     * @param abilityId 学习后保存的能力实例标识
     * @param definition 已解析的能力定义，底层可从世界素材读取时为空
     * @param templateId 实例绑定的规则标识，底层独立定义可为空
     */
    public record LearnAbility(String abilityId, AbilityDefinition definition, String templateId) implements ApAction {
        /** 使用世界素材中登记的技能。
         * @param abilityId 已登记的技能标识
         */
        public LearnAbility(String abilityId) { this(abilityId, null, null); }
        /** 返回学习能力动作的类型标识。 */
        public String type() { return "learn_ability"; } }
    public record EquipItem(String itemName, String slot) implements ApAction { public String type() { return "equip_item"; } }
    public record UseItem(String itemName) implements ApAction { public String type() { return "use_item"; } }
    public record OfferQuest(ApQuest quest) implements ApAction { public String type() { return "offer_quest"; } }
    public record AcceptQuest(String questId) implements ApAction { public String type() { return "accept_quest"; } }
    public record ProgressQuest(String questId, int objectiveIndex, int amount) implements ApAction { public String type() { return "progress_quest"; } }
    public record CompleteQuest(String questId) implements ApAction { public String type() { return "complete_quest"; } }
    public record FailQuest(String questId, String reason) implements ApAction { public String type() { return "fail_quest"; } }
    public record RegisterNpc(String npcId, String name, String npcType, String location, long seed) implements ApAction { public String type() { return "register_npc"; } }
    public record InteractNpc(String npcId, String action, ApNpc.InteractionResult result, long seed, long day) implements ApAction { public InteractNpc(String npcId, String action, ApNpc.InteractionResult result, long seed) { this(npcId, action, result, seed, 0); } public String type() { return "interact_npc"; } }
    public record TickNpcs(long seed, long day) implements ApAction { public TickNpcs(long seed) { this(seed, 0); } public String type() { return "tick_npcs"; } }
    public record RecordNpcLifeEvent(String npcId, String description, long day) implements ApAction { public String type() { return "record_npc_life_event"; } }
    public record SetNpcStatus(String npcId, ApNpc.Status status) implements ApAction { public String type() { return "set_npc_status"; } }
    public record AcquireItem(String name, String category, String tier, long seed) implements ApAction { public String type() { return "acquire_item"; } }
    /** 按 GM 指定的素材发起普通遭遇。
     * @param encounterName 遭遇展示名称
     * @param monsterId 已登记的怪物素材标识
     */
    public record StartBattle(String encounterName, String monsterId) implements ApAction {
        /** 返回开始战斗的动作标识。 */
        public String type() { return "start_battle"; }
    }
    public record BattleAction(ApBattle.ActionKind action, String targetId, String abilityId, String itemName) implements ApAction { public String type() { return "battle_action"; } }
    public record AdvanceBattleTurn() implements ApAction { public String type() { return "advance_battle_turn"; } }
    public record SettleBattle(ApBattle.Result result) implements ApAction { public String type() { return "settle_battle"; } }
    /** 记录 AP 引擎确认的状态变化，供叙事和接口返回。
     * @param type AP 事件类型
     * @param amount 实际变化量，单位由事件类型决定
     * @param levelsGained 本次提升的等级数，未升级时为零
     * @param description 供叙事使用的权威结果
     * @param subject 行动者标识，可为空字符串
     * @param target 受影响的实体标识，可为空字符串
     */
    public record ApEvent(String type, long amount, int levelsGained, String description, String subject, String target) {
        /** 创建简洁的状态变化事件。
         * @param type AP 事件类型
         * @param amount 事件的数值
         */
        public ApEvent(String type, long amount) {
            this(type, amount, 0);
        }

        /** 创建数值变化事件并记录升级。
         * @param type AP 事件类型
         * @param amount 事件的数值
         * @param levelsGained 本次提升的等级数
         */
        public ApEvent(String type, long amount, int levelsGained) {
            this(type, amount, levelsGained, type + "：" + amount, "", "");
        }

        /** 创建带可读结果和参与者信息的事件。
         * @param type AP 事件类型
         * @param amount 事件的数值
         * @param description 实际结算结果
         * @param subject 行动者标识，可为空字符串
         * @param target 受影响的实体标识，可为空字符串
         */
        public ApEvent(String type, long amount, String description, String subject, String target) {
            this(type, amount, 0, description, subject, target);
        }
    }
    public record ApProjection(ApState state) {}
}
