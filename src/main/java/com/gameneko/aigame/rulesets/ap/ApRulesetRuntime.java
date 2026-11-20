package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.RulesetResolution;
import com.gameneko.aigame.engine.RulesetRuntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

/** AP runtime. State transitions are immutable and every player-visible fact is typed. */
public final class ApRulesetRuntime implements RulesetRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApAction,
        ApRulesetRuntime.ApEvent, ApRulesetRuntime.ApProjection> {
    private static final Map<String, Archetype> ARCHETYPES = Map.of(
            "swordsman", new Archetype(14, 12, 8, 12, 9, 9, "短剑", "+2", "皮背心"),
            "mage", new Archetype(7, 10, 16, 8, 14, 10, "学徒法杖", "+1", "法袍"),
            "ranger", new Archetype(11, 15, 10, 10, 12, 9, "短弓", "+1", "轻便布衣"),
            "alchemist", new Archetype(9, 10, 15, 9, 11, 13, "投石索", "+1", "工作围裙"));
    private static final Archetype DEFAULT = new Archetype(10, 10, 10, 10, 10, 10, "铁匕首", "+2", "旅行衫");
    private static final String[] THREAT_LEVELS = {"Dormant", "Low", "Moderate", "High", "Severe", "Crisis"};

    @Override public String id() { return "ap"; }
    @Override public String version() { return "baseline-1"; }

    public ApState initialize(ApInitializationRequest request) {
        return initialize(request, null);
    }

    public ApState initialize(ApInitializationRequest request, WorldDefinition world) {
        if (request == null) throw new IllegalArgumentException("AP initialization input is required");
        String archetype = request.archetype() == null ? "swordsman" : request.archetype().toLowerCase().trim();
        Archetype preset = ARCHETYPES.getOrDefault(archetype, DEFAULT);
        String difficulty = request.difficulty() == null ? "adventure" : request.difficulty().toLowerCase().trim();
        WorldDefinition.DifficultyDefinition diff = world == null
                ? new WorldDefinition.DifficultyDefinition(7, 15, 3, 2, "冒险", "")
                : world.difficulties().get(difficulty);
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
                List.of(), List.of(), List.of(), List.of(), ApSystems.empty());
    }

    @Override public ActionValidation validate(ApState state, ApAction action) { return validate(state, action, null); }

    public ActionValidation validate(ApState state, ApAction action, WorldDefinition world) {
        if (state == null || action == null) return ActionValidation.reject("State and action are required");
        if (state.hpCurrent() <= 0 && !(action instanceof SettleBattle)) return ActionValidation.reject("Character is not alive");
        if (action instanceof LearnAbility learn) {
            if (world == null || !world.abilities().containsKey(learn.abilityId())) return ActionValidation.reject("Unknown ability");
            if (state.hasAbility(learn.abilityId())) return ActionValidation.reject("Ability already learned");
            for (var requirement : world.abilities().get(learn.abilityId()).prerequisites().entrySet())
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
        if (action instanceof ClaimQuestReward reward) {
            return reward.questId() != null && !reward.questId().isBlank() && state.quests().stream().noneMatch(q -> q.questId().equals(reward.questId()))
                    && reward.xp() >= 0 && reward.copper() >= 0 && reward.rations() >= 0 && reward.potions() >= 0
                    && (reward.xp() > 0 || reward.copper() > 0 || reward.rations() > 0 || reward.potions() > 0)
                    ? ActionValidation.accept() : ActionValidation.reject("Quest reward is invalid or already claimed");
        }
        if (action instanceof GrantExperience grant) return grant.amount() > 0 ? ActionValidation.accept() : ActionValidation.reject("Experience must be positive");
        if (action instanceof ConsumeRation) return state.resources().rations() > 0 ? ActionValidation.accept() : ActionValidation.reject("Not enough rations");
        if (action instanceof UsePotion) return state.resources().potions() > 0 && state.hpCurrent() < state.hpMax() ? ActionValidation.accept() : ActionValidation.reject("Potion cannot be used");
        if (action instanceof Rest || action instanceof AdvanceDay) return ActionValidation.accept();
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
        if (action instanceof InteractNpc interact) return npc(state, interact.npcId()) != null && interact.action() != null && !interact.action().isBlank() ? ActionValidation.accept() : ActionValidation.reject("NPC interaction is invalid");
        if (action instanceof RecordNpcLifeEvent lifeEvent) return npc(state, lifeEvent.npcId()) != null && lifeEvent.description() != null && !lifeEvent.description().isBlank() ? ActionValidation.accept() : ActionValidation.reject("NPC life event is invalid");
        if (action instanceof SetNpcStatus update) return npc(state, update.npcId()) != null && update.status() != null ? ActionValidation.accept() : ActionValidation.reject("NPC status update is invalid");
        if (action instanceof TickNpcs) return ActionValidation.accept();
        if (action instanceof AcquireItem acquire) return world != null && acquire.name() != null && !acquire.name().isBlank()
                && world.itemTemplates().containsKey(acquire.category() + ":" + acquire.tier())
                ? ActionValidation.accept() : ActionValidation.reject("Unknown item template");
        if (action instanceof StartBattle start) {
            if (state.systems().battle() != null || start.enemyCount() <= 0 || start.enemyCount() > 20) return ActionValidation.reject("Battle cannot be started");
            if (world != null && start.region() != null && !start.region().isBlank()) {
                if (!world.regionMonsterTypes().containsKey(start.region())) return ActionValidation.reject("Unknown region");
                if (world.regionMonsterTypes().get(start.region()).isEmpty()) return ActionValidation.reject("Region has no eligible monsters");
            }
            return ActionValidation.accept();
        }
        if (action instanceof BattleAction battle) return validateBattle(state, battle);
        if (action instanceof AdvanceBattleTurn) return state.systems().battle() != null && state.systems().battle().active() ? ActionValidation.accept() : ActionValidation.reject("No active battle");
        if (action instanceof SettleBattle settle) return state.systems().battle() != null && !state.systems().battle().rewardsSettled()
                && state.systems().battle().result() != ApBattle.Result.ACTIVE
                && state.systems().battle().result() == settle.result()
                ? ActionValidation.accept() : ActionValidation.reject("Battle result is not ready to settle");
        return ActionValidation.reject("Unsupported AP action");
    }

    private ActionValidation validateBattle(ApState state, BattleAction action) {
        ApBattle.Snapshot battle = state.systems().battle();
        if (battle == null || !battle.active()) return ActionValidation.reject("No active battle");
        if (action.action() == null) return ActionValidation.reject("Battle action is required");
        ApBattle.Combatant actor = battle.currentActor();
        int cost = switch (action.action()) { case ATTACK -> 2; case HEAVY -> 3; case DEFEND, DODGE -> 2; case ITEM, FOCUS -> 1; case WAIT -> 0; case SPELL, SKILL -> 0; };
        if (action.action() == ApBattle.ActionKind.ITEM && (!actor.player() || state.inventory().stream().noneMatch(i -> i.name().equals(action.itemName()) && i.quantity() > 0 && i.effect().usable()))) return ActionValidation.reject("Usable item not found");
        if (action.action() == ApBattle.ActionKind.SKILL || action.action() == ApBattle.ActionKind.SPELL) {
            Ability ability = findAbility(state, action.abilityId());
            if (ability == null || (action.action() == ApBattle.ActionKind.SKILL && !"skill".equals(ability.kind()))
                    || (action.action() == ApBattle.ActionKind.SPELL && !"spell".equals(ability.kind()))) return ActionValidation.reject("Ability is not learned for this action");
            if ("active".equals(ability.mechanic().activation()) == false && action.action() == ApBattle.ActionKind.SKILL) return ActionValidation.reject("Passive abilities cannot be activated");
            if (actor.currentAp() < ability.apCost() || actor.sp() < ability.spCost()) return ActionValidation.reject("Not enough combat resources");
            if (ability.mechanic().oncePerCombat() && actor.usedAbilities().contains(ability.id())) return ActionValidation.reject("Ability was already used this battle");
        }
        if (actor.currentAp() < cost) return ActionValidation.reject("Not enough action points");
        if (Set.of(ApBattle.ActionKind.ATTACK, ApBattle.ActionKind.HEAVY).contains(action.action()) && target(battle, action.targetId()) == null) return ActionValidation.reject("Battle target is required");
        if (action.action() == ApBattle.ActionKind.SKILL || action.action() == ApBattle.ActionKind.SPELL) {
            Ability ability = findAbility(state, action.abilityId());
            var category = ability.mechanic().category();
            boolean needsTarget = switch (category) {
                case DAMAGE, DAMAGE_DEBUFF, DOT, DEBUFF -> true;
                default -> false;
            };
            if (needsTarget && target(battle, action.targetId()) == null) return ActionValidation.reject("Ability target is required");
        }
        return ActionValidation.accept();
    }

    @Override public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action) { return resolve(state, action, 0, null); }
    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action, int hpRecoveryBonus) { return resolve(state, action, hpRecoveryBonus, null); }

    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action, int hpRecoveryBonus, WorldDefinition world) {
        ActionValidation validation = validate(state, action, world);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        if (action instanceof LearnAbility learn) {
            var definition = Objects.requireNonNull(world).abilities().get(learn.abilityId());
            Ability ability = new Ability(definition.id(), definition.kind(), definition.tier(), definition.apCost(), definition.spCost(), definition.power(), definition.name(), definition.effectHint(), definition.description(), definition.mechanic());
            ApState next = "spell".equals(definition.kind()) ? state.withSpellsAdd(ability) : state.withSkillsAdd(ability);
            return result(next, new ApEvent("learn_ability", 1));
        }
        if (action instanceof EquipItem equip) return result(state.withEquipment("weapon".equals(equip.slot())
                ? state.equipment().withWeapon(equip.itemName()) : state.equipment().withArmor(equip.itemName())),
                new ApEvent("equip_item", 1));
        if (action instanceof UseItem use) return useItem(state, use);
        if (action instanceof ClaimQuestReward reward) {
            ApState next = state.withResources(state.resources().add(reward.copper(), reward.rations(), reward.potions())).withQuestsAdd(new QuestReference(reward.questId()));
            if (reward.xp() > 0) next = applyExperience(next, reward.xp());
            return result(next, new ApEvent("quest_reward", reward.xp() + reward.copper() + reward.rations() + reward.potions()));
        }
        if (action instanceof GrantExperience grant) {
            ApState next = applyExperience(state, grant.amount());
            return result(next, new ApEvent("experience", grant.amount(), next.level() - state.level()));
        }
        if (action instanceof Rest) return result(state.withVitality(Math.min(state.hpMax(), state.hpCurrent() + 2), Math.min(state.spMax(), state.spCurrent() + 2)), new ApEvent("rest", 2));
        if (action instanceof ConsumeRation) return result(state.withResources(state.resources().withRations(state.resources().rations() - 1)), new ApEvent("consume_ration", 1));
        if (action instanceof UsePotion) return useItem(state, new UseItem("potion"));
        if (action instanceof AdvanceDay) return advanceDay(state, hpRecoveryBonus);
        if (action instanceof OfferQuest offer) return result(state.withSystems(state.systems().withQuest(offer.quest())), new ApEvent("quest_offered", 1));
        if (action instanceof AcceptQuest accept) return questTransition(state, accept.questId(), q -> q.accept(), "quest_accepted");
        if (action instanceof ProgressQuest progress) return questTransition(state, progress.questId(), q -> q.progress(progress.objectiveIndex(), progress.amount()), "quest_progress");
        if (action instanceof CompleteQuest complete) {
            ApQuest q = quest(state, complete.questId(), ApQuest.Status.ACTIVE);
            ApState next = state.withSystems(state.systems().replaceQuest(q.complete()));
            next = next.withResources(next.resources().add(q.reward().copper(), q.reward().rations(), q.reward().potions()));
            if (q.reward().experience() > 0) next = applyExperience(next, q.reward().experience());
            return result(next, new ApEvent("quest_completed", q.reward().experience() + q.reward().copper()));
        }
        if (action instanceof FailQuest fail) return questTransition(state, fail.questId(), q -> q.fail(fail.reason()), "quest_failed");
        if (action instanceof RegisterNpc register) return result(state.withSystems(state.systems().withNpc(createNpc(register, world))), new ApEvent("npc_registered", 1));
        if (action instanceof InteractNpc interact) {
            ApNpc current = npc(state, interact.npcId());
            var random = new java.util.SplittableRandom(interact.seed());
            int magnitude = random.nextInt(3, 9);
            int relation = interact.result() == ApNpc.InteractionResult.POSITIVE ? magnitude
                    : interact.result() == ApNpc.InteractionResult.NEGATIVE ? -magnitude : random.nextInt(0, 3);
            int mood = interact.result() == ApNpc.InteractionResult.POSITIVE ? random.nextInt(1, 4)
                    : interact.result() == ApNpc.InteractionResult.NEGATIVE ? -random.nextInt(1, 4) : 0;
            return result(state.withSystems(state.systems().replaceNpc(current.interact(interact.day(), interact.action(), interact.result(), relation, mood))), new ApEvent("npc_interaction", relation));
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
            Item item = createItem(world, acquire.name(), acquire.category(), acquire.tier(), new java.util.SplittableRandom(acquire.seed()));
            List<Item> inventory = new ArrayList<>(state.inventory());
            int index = -1;
            for (int i = 0; i < inventory.size(); i++) if (inventory.get(i).name().equals(item.name()) && inventory.get(i).type().equals(item.type())) { index = i; break; }
            if (index >= 0) {
                Item old = inventory.get(index);
                inventory.set(index, new Item(old.name(), old.type(), old.damage(), Math.addExact(old.quantity(), 1), old.effect(), old.price(), old.value(), old.weaponDamageBonus(), old.armorMitigationBonus()));
            } else inventory.add(item);
            return result(state.withInventory(inventory), new ApEvent("item_acquired", 1));
        }
        if (action instanceof StartBattle start) return startBattle(state, start, world);
        if (action instanceof BattleAction battle) return battleAction(state, battle, world);
        if (action instanceof AdvanceBattleTurn) return advanceBattle(state);
        if (action instanceof SettleBattle settle) return settleBattle(state, settle.result());
        throw new IllegalArgumentException("Unsupported AP action");
    }

    private RulesetResolution<ApState, ApEvent> useItem(ApState state, UseItem use) {
        if (isPotion(use.itemName())) {
            int hp = Math.min(state.hpMax(), state.hpCurrent() + 10);
            return result(state.withVitality(hp, state.spCurrent()).withResources(state.resources().withPotions(state.resources().potions() - 1)), new ApEvent("use_item", hp - state.hpCurrent()));
        }
        Item used = state.inventory().stream().filter(item -> item.name().equals(use.itemName())).findFirst().orElseThrow();
        ApItemEffect effect = used.effect();
        int hp = Math.min(state.hpMax(), Math.addExact(state.hpCurrent(), effect.hpRecovery()));
        int sp = Math.min(state.spMax(), Math.addExact(state.spCurrent(), effect.spRecovery()));
        List<StatusEffect> statuses = effect.clearStatuses() ? new ArrayList<>() : new ArrayList<>(state.statusEffects());
        effect.statusApplications().forEach(status -> statuses.add(new StatusEffect(status.name(), status.duration(), status.penalty())));
        return result(state.withVitality(hp, sp).withStatusEffects(statuses).withInventory(consumeItem(state.inventory(), used)), new ApEvent("use_item", hp - state.hpCurrent() + sp - state.spCurrent()));
    }

    private RulesetResolution<ApState, ApEvent> advanceDay(ApState state, int bonus) {
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

    private RulesetResolution<ApState, ApEvent> startBattle(ApState state, StartBattle start, WorldDefinition world) {
        RandomGenerator random = new java.util.SplittableRandom(start.seed());
        String region = start.region() == null || start.region().isBlank() ? "" : start.region();
        List<ApBattle.Combatant> combatants = new ArrayList<>();
        combatants.add(playerCombatant(state));
        for (int i = 0; i < start.enemyCount(); i++) {
            String type = chooseMonsterType(world, region, random);
            combatants.add(monsterCombatant(type, i + 1, world, state.difficulty(), random));
        }
        combatants.sort(Comparator.comparingInt(ApBattle.Combatant::speed).reversed().thenComparing(ApBattle.Combatant::id));
        List<String> order = combatants.stream().map(ApBattle.Combatant::id).toList();
        ApBattle.Snapshot battle = new ApBattle.Snapshot(1, 0, order, combatants, List.of(), true, ApBattle.Result.ACTIVE, start.encounterName(), start.seed(), false);
        return result(state.withSystems(state.systems().withBattle(battle)), new ApEvent("battle_started", combatants.size() - 1));
    }

    public MonsterSnapshot generateMonster(WorldDefinition world, String region, String type, RandomGenerator random) {
        if (world == null || random == null) throw new IllegalArgumentException("World and random source are required");
        if (region == null || !world.regionMonsterTypes().containsKey(region)) throw new IllegalArgumentException("Unknown region: " + region);
        List<String> pool = world.regionMonsterTypes().get(region);
        if (!pool.contains(type)) throw new IllegalArgumentException("Monster type is outside region pool");
        ApBattle.Combatant monster = monsterCombatant(type, 0, world, "adventure", random);
        return new MonsterSnapshot(monster.id(), monster.name(), type, monster.hp(), monster.power(), monster.mitigation(), monster.speed(), monster.maxAp(), monster.experienceReward(), monster.copperReward());
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

    private String chooseMonsterType(WorldDefinition world, String region, RandomGenerator random) {
        if (world == null || region.isBlank() || !world.regionMonsterTypes().containsKey(region)) return "low";
        List<String> pool = world.regionMonsterTypes().get(region);
        return pool.isEmpty() ? "low" : pool.get(random.nextInt(pool.size()));
    }

    private ApBattle.Combatant playerCombatant(ApState state) {
        int power = state.strength() + weaponDamage(state);
        int mitigation = Math.floorDiv(state.constitution(), 3) + armorMitigation(state);
        int maxAp = Math.min(12, 4 + state.dexterity() / 3 + state.level() / 3);
        return new ApBattle.Combatant("player", state.characterName(), true, "", state.hpCurrent(), state.hpMax(), state.spCurrent(), state.spMax(), power, mitigation, state.dexterity() + state.level(), maxAp, maxAp, 0, 0, false, false, List.of(), 0, 0, List.of(), Set.of());
    }

    private ApBattle.Combatant monsterCombatant(String type, int index, WorldDefinition world, String difficulty, RandomGenerator random) {
        WorldDefinition.MonsterDefinition definition = world == null ? null : world.monsters().get(type);
        int hp = definition == null ? random.nextInt(8, 19) : definition.hpRange().roll(random);
        int power = definition == null ? random.nextInt(3, 8) : definition.powerRange().roll(random);
        int mitigation = definition == null ? random.nextInt(0, 3) : definition.mitigationRange().roll(random);
        int speed = definition == null ? random.nextInt(6, 11) : (int) Math.floor(definition.speedRange().roll(random) * definition.speedMultiplier());
        int ap = definition == null ? 4 : definition.actionPoints();
        int xp = definition == null ? 20 : definition.experienceRange().roll(random);
        int gold = definition == null ? random.nextInt(0, 9) : definition.lootCopperRange().roll(random);
        if (world != null && world.difficulties().containsKey(difficulty)) {
            var d = world.difficulties().get(difficulty);
            hp = scale(hp, d.enemyHpMultiplierPercent()); power = scale(power, d.enemyPowerMultiplierPercent());
            mitigation = scale(mitigation, d.enemyMitigationMultiplierPercent()); ap = Math.max(1, scale(ap, d.enemyApMultiplierPercent()));
            xp = scale(xp, d.experienceMultiplierPercent()); gold = scale(gold, d.goldMultiplierPercent());
        }
        return new ApBattle.Combatant("enemy-" + index, type + "-" + index, false, type, Math.max(1, hp), Math.max(1, hp), 0, 0, Math.max(1, power), Math.max(0, mitigation), Math.max(1, speed), Math.max(1, ap), Math.max(1, ap), 0, 0, false, false, List.of(), Math.max(1, xp), Math.max(0, gold), List.of(), Set.of());
    }

    private RulesetResolution<ApState, ApEvent> battleAction(ApState state, BattleAction action, WorldDefinition world) {
        ApBattle.Snapshot battle = state.systems().battle();
        ApBattle.Combatant actor = battle.currentActor();
        List<ApBattle.Combatant> values = new ArrayList<>(battle.combatants());
        ApBattle.Combatant target = action.targetId() == null ? null : target(battle, action.targetId());
        int cost = switch (action.action()) { case ATTACK -> 2; case HEAVY -> 3; case DEFEND, DODGE -> 2; case ITEM, FOCUS -> 1; case WAIT -> 0; case SPELL, SKILL -> 0; };
        int damage = 0, healing = 0; String outcome = ""; String effect = "";
        if (action.action() == ApBattle.ActionKind.ATTACK || action.action() == ApBattle.ActionKind.HEAVY) {
            int power = action.action() == ApBattle.ActionKind.HEAVY ? scale(actor.power(), 150) : actor.power();
            DamageResult hit = damage(power, target, false);
            damage = hit.damage(); outcome = hit.outcome();
            target = target.update(target.hp() - damage, target.sp(), target.power(), target.mitigation(), target.currentAp(), target.guard(), target.exposedRounds(), false, false, target.statuses(), target.usedAbilities());
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), action.action() == ApBattle.ActionKind.HEAVY ? 1 : actor.exposedRounds(), actor.defending(), actor.dodging(), actor.statuses(), actor.usedAbilities());
            values = replace(values, actor); if (target != null) values = replace(values, target);
        } else if (action.action() == ApBattle.ActionKind.DEFEND) {
            int bank = Math.min(2, actor.currentAp() - cost);
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost - bank, actor.guard() + bank, actor.exposedRounds(), true, false, actor.statuses(), actor.usedAbilities()); values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.DODGE) {
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), false, true, actor.statuses(), actor.usedAbilities()); values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.FOCUS) {
            healing = Math.min(2, actor.maxSp() - actor.sp()); actor = actor.update(actor.hp(), actor.sp() + healing, actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), actor.defending(), actor.dodging(), actor.statuses(), actor.usedAbilities()); values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.ITEM) {
            if ("potion".equalsIgnoreCase(action.itemName()) && state.resources().potions() > 0) {
                healing = Math.min(10, actor.maxHp() - actor.hp());
                actor = actor.update(actor.hp() + healing, actor.sp(), actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), actor.defending(), actor.dodging(), actor.statuses(), actor.usedAbilities());
                state = state.withResources(state.resources().withPotions(state.resources().potions() - 1));
            } else {
                Item used = state.inventory().stream().filter(item -> item.name().equals(action.itemName())).findFirst().orElseThrow();
                ApItemEffect itemEffect = used.effect();
                int hpGain = Math.min(itemEffect.hpRecovery(), actor.maxHp() - actor.hp());
                int spGain = Math.min(itemEffect.spRecovery(), actor.maxSp() - actor.sp());
                healing = hpGain + spGain;
                List<ApBattle.CombatStatus> statuses = itemEffect.clearStatuses() ? List.of() : actor.statuses();
                actor = actor.update(actor.hp() + hpGain, actor.sp() + spGain, actor.power(), actor.mitigation(), actor.currentAp() - cost, actor.guard(), actor.exposedRounds(), actor.defending(), actor.dodging(), statuses, actor.usedAbilities());
                state = state.withInventory(consumeItem(state.inventory(), used));
            }
            values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.WAIT) {
            actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), 0, actor.guard() + Math.min(4, actor.currentAp()), actor.exposedRounds(), false, false, actor.statuses(), actor.usedAbilities()); values = replace(values, actor);
        } else if (action.action() == ApBattle.ActionKind.SKILL || action.action() == ApBattle.ActionKind.SPELL) {
            Ability ability = findAbility(state, action.abilityId());
            int ap = Math.max(0, ability.apCost());
            var mechanic = ability.mechanic();
            Set<String> usedAbilities = new HashSet<>(actor.usedAbilities());
            if (mechanic.oncePerCombat()) usedAbilities.add(ability.id());
            List<ApBattle.CombatStatus> statuses = new ArrayList<>(actor.statuses());
            int nextPower = actor.power(); int nextGuard = actor.guard(); int nextHp = actor.hp();
            int spCost = action.action() == ApBattle.ActionKind.SPELL ? ability.spCost() : 0;
            switch (mechanic.category()) {
                case DAMAGE, ATTACK_MODIFIER, DAMAGE_DEBUFF, DOT -> {
                    int basePower = ability.power() > 0 ? ability.power() : mechanic.power() > 0 ? mechanic.power() : actor.power();
                    int power = scale(basePower, mechanic.powerMultiplierPercent());
                    if (mechanic.category() == WorldDefinition.AbilityMechanic.Category.ATTACK_MODIFIER && mechanic.powerMultiplierPercent() == 100) power = scale(actor.power(), 150);
                    DamageResult hit = damage(power, target, action.action() == ApBattle.ActionKind.SPELL);
                    damage = hit.damage(); outcome = hit.outcome();
                    target = target.update(target.hp() - damage, target.sp(), target.power(), target.mitigation(), target.currentAp(), target.guard(), target.exposedRounds(), false, false, target.statuses(), target.usedAbilities());
                    if (mechanic.category() == WorldDefinition.AbilityMechanic.Category.DAMAGE_DEBUFF || mechanic.category() == WorldDefinition.AbilityMechanic.Category.DOT) {
                        String statusEffect = mechanic.effect().isBlank() ? "hp_drain" : mechanic.effect();
                        var targetStatuses = new ArrayList<>(target.statuses());
                        targetStatuses.add(new ApBattle.CombatStatus(ability.name(), statusEffect, mechanic.amount(), Math.max(1, mechanic.duration())));
                        target = target.update(target.hp(), target.sp(), target.power(), target.mitigation(), target.currentAp(), target.guard(), target.exposedRounds(), target.defending(), target.dodging(), targetStatuses, target.usedAbilities());
                    }
                    values = replace(values, target);
                    if (mechanic.activation().equals("active") && mechanic.category() == WorldDefinition.AbilityMechanic.Category.ATTACK_MODIFIER) actor = actor.update(actor.hp(), actor.sp(), actor.power(), actor.mitigation(), actor.currentAp(), actor.guard(), 1, actor.defending(), actor.dodging(), statuses, usedAbilities);
                }
                case HEAL, SELF_HEAL -> { healing = Math.min(mechanic.healAmount(), actor.maxHp() - actor.hp()); nextHp += healing; if (mechanic.clearStatuses()) statuses.clear(); }
                case DEBUFF -> {
                    var targetStatuses = new ArrayList<>(target.statuses());
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
                default -> { effect = ability.effectHint(); }
            }
            actor = actor.update(nextHp, actor.sp() - spCost, actor.power(), actor.mitigation(), actor.currentAp() - ap, nextGuard, actor.exposedRounds(), actor.defending(), actor.dodging(), statuses, usedAbilities);
            values = replace(values, actor); cost = ap; effect = ability.effectHint(); if (outcome.isBlank()) outcome = "ABILITY";
        }
        ApBattle.BattleEvent event = new ApBattle.BattleEvent(battle.round(), actor.id(), action.action(), target == null ? null : target.id(), cost, action.action() == ApBattle.ActionKind.SPELL ? findAbility(state, action.abilityId()).spCost() : 0, actor.power(), damage, healing, outcome, effect);
        List<ApBattle.BattleEvent> log = new ArrayList<>(battle.log()); log.add(event);
        ApBattle.Result result = battleResult(values);
        ApBattle.Snapshot nextBattle = battle.with(battle.round(), battle.turnIndex(), values, log, result == ApBattle.Result.ACTIVE, result, false);
        return result(state.withVitality(playerHp(values, state.hpCurrent()), playerSp(values, state.spCurrent())).withSystems(state.systems().withBattle(nextBattle)), new ApEvent("battle_action", damage));
    }

    private RulesetResolution<ApState, ApEvent> advanceBattle(ApState state) {
        ApBattle.Snapshot battle = state.systems().battle();
        int next = battle.turnIndex(); int round = battle.round();
        for (int i = 0; i < battle.turnOrder().size(); i++) { next = (next + 1) % battle.turnOrder().size(); if (battle.combatant(battle.turnOrder().get(next)).alive()) break; }
        boolean newRound = next <= battle.turnIndex();
        int selected = next;
        if (newRound) round++;
        List<ApBattle.Combatant> refreshed = new ArrayList<>();
        for (ApBattle.Combatant combatant : battle.combatants()) refreshed.add(tickCombatant(combatant, newRound, combatant.id().equals(battle.turnOrder().get(selected))));
        if (refreshed.stream().noneMatch(c -> c.player() && c.alive())) {
            ApBattle.Snapshot defeated = battle.with(round, next, refreshed, battle.log(), false, ApBattle.Result.DEFEAT, false);
            return result(state.withVitality(0, playerSp(refreshed, state.spCurrent())).withSystems(state.systems().withBattle(defeated)), new ApEvent("battle_defeat", 0));
        }
        return result(state.withVitality(playerHp(refreshed, state.hpCurrent()), playerSp(refreshed, state.spCurrent())).withSystems(state.systems().withBattle(battle.with(round, next, refreshed, battle.log(), true, battle.result(), false))), new ApEvent("battle_turn", round));
    }

    private RulesetResolution<ApState, ApEvent> settleBattle(ApState state, ApBattle.Result result) {
        ApBattle.Snapshot battle = state.systems().battle();
        ApBattle.Combatant player = battle.combatants().stream().filter(ApBattle.Combatant::player).findFirst().orElseThrow();
        ApState next = state.withVitality(player.hp(), player.sp());
        if (result == ApBattle.Result.VICTORY) {
            long xp = battle.combatants().stream().filter(c -> !c.player()).mapToLong(ApBattle.Combatant::experienceReward).sum();
            long copper = battle.combatants().stream().filter(c -> !c.player()).mapToLong(ApBattle.Combatant::copperReward).sum();
            next = applyExperience(next.withResources(next.resources().add(copper, 0, 0)), xp);
            List<Item> inventory = new ArrayList<>(next.inventory());
            for (String loot : battle.combatants().stream().filter(c -> !c.player()).flatMap(c -> c.lootItems().stream()).toList()) {
                inventory.add(new Item(loot, "loot", "", 1));
            }
            next = next.withInventory(inventory);
            battle = battle.with(battle.round(), battle.turnIndex(), battle.combatants(), battle.log(), false, result, true);
            return result(next.withSystems(next.systems().withBattle(battle)), new ApEvent("battle_settled", xp));
        }
        battle = battle.with(battle.round(), battle.turnIndex(), battle.combatants(), battle.log(), false, result, true);
        return result(next.withSystems(next.systems().withBattle(battle)), new ApEvent("battle_settled", 0));
    }

    private ApBattle.Result battleResult(List<ApBattle.Combatant> combatants) {
        boolean playerAlive = combatants.stream().anyMatch(c -> c.player() && c.alive());
        boolean enemiesAlive = combatants.stream().anyMatch(c -> !c.player() && c.alive());
        return !playerAlive ? ApBattle.Result.DEFEAT : !enemiesAlive ? ApBattle.Result.VICTORY : ApBattle.Result.ACTIVE;
    }

    private DamageResult damage(int power, ApBattle.Combatant target, boolean magic) {
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
    public record MonsterSnapshot(String id, String name, String type, int hp, int power, int mitigation, int speed, int actionPoints, long experience, long copper) {}

    public record ApState(String characterName, int age, String gender, String archetype, String difficulty, int level, long xp, long xpToNext, int strength, int dexterity, int constitution, int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent, int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent, List<StatusEffect> statusEffects, List<Ability> skills, List<Ability> spells, List<QuestReference> quests, ApSystems systems) implements GameState {
        public ApState { inventory = inventory == null ? List.of() : List.copyOf(inventory); statusEffects = statusEffects == null ? List.of() : List.copyOf(statusEffects); skills = skills == null ? List.of() : List.copyOf(skills); spells = spells == null ? List.of() : List.copyOf(spells); quests = quests == null ? List.of() : List.copyOf(quests); systems = systems == null ? ApSystems.empty() : systems; }
        public ApState(String characterName, int age, String gender, String archetype, String difficulty, int level, long xp, long xpToNext, int strength, int dexterity, int constitution, int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent, int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent) { this(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, List.of(), List.of(), List.of(), List.of(), ApSystems.empty()); }
        public ApState(String characterName, int age, String gender, String archetype, String difficulty, int level, long xp, long xpToNext, int strength, int dexterity, int constitution, int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent, int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent, List<StatusEffect> statusEffects) { this(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, List.of(), List.of(), List.of(), ApSystems.empty()); }
        public ApState withVitality(int hp, int sp) { return copy(hp, hpMax, sp, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, quests, systems); }
        public ApState withResources(Resources value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, value, uniqueTalent, statusEffects, skills, spells, quests, systems); }
        public ApState withStatusEffect(StatusEffect effect) { var next = new ArrayList<>(statusEffects); next.add(effect); return withStatusEffects(next); }
        public ApState withStatusEffects(List<StatusEffect> value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, value, skills, spells, quests, systems); }
        public ApState withProgression(int value, long nextXp, long nextNeed) { return new ApState(characterName, age, gender, archetype, difficulty, value, nextXp, nextNeed, strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, quests, systems); }
        public ApState withSkillsAdd(Ability ability) { var next = new ArrayList<>(skills); next.add(ability); return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, next, spells, quests, systems); }
        public ApState withSpellsAdd(Ability ability) { var next = new ArrayList<>(spells); next.add(ability); return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, next, quests, systems); }
        public ApState withQuestsAdd(QuestReference quest) { var next = new ArrayList<>(quests); next.add(quest); return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, next, systems); }
        public ApState withEquipment(Equipment value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, value, resources, uniqueTalent, statusEffects, skills, spells, quests, systems); }
        public ApState withInventory(List<Item> value) { return copy(hpCurrent, hpMax, spCurrent, spMax, value, equipment, resources, uniqueTalent, statusEffects, skills, spells, quests, systems); }
        public ApState withSystems(ApSystems value) { return copy(hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, quests, value); }
        public List<ApQuest> questLog() { return systems.questLog(); }
        public List<ApNpc> npcs() { return systems.npcs(); }
        public ApBattle.Snapshot battle() { return systems.battle(); }
        private ApState copy(int hp, int maxHp, int sp, int maxSp, List<Item> items, Equipment gear, Resources values, Talent talent, List<StatusEffect> effects, List<Ability> learnedSkills, List<Ability> learnedSpells, List<QuestReference> refs, ApSystems stateSystems) { return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity, constitution, intelligence, wisdom, charisma, hp, maxHp, sp, maxSp, items, gear, values, talent, effects, learnedSkills, learnedSpells, refs, stateSystems); }
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
    public record Ability(String id, String kind, int tier, int apCost, int spCost, int power, String name, String effectHint, String description, WorldDefinition.AbilityMechanic mechanic) { public Ability(String id, String kind, int tier, int apCost, int spCost, int power, String name, String effectHint, String description) { this(id, kind, tier, apCost, spCost, power, name, effectHint, description, WorldDefinition.AbilityMechanic.none()); } }
    public record QuestReference(String questId) {}
    /**
     * Internal AP command model. TODO(C): remove once the AP rules implementation
     * is migrated behind a fully generic adapter; application/API code must use ActionIntent.
     */
    public sealed interface ApAction permits Rest, ConsumeRation, UsePotion, AdvanceDay, GrantExperience, LearnAbility, EquipItem, UseItem, ClaimQuestReward, OfferQuest, AcceptQuest, ProgressQuest, CompleteQuest, FailQuest, RegisterNpc, InteractNpc, TickNpcs, RecordNpcLifeEvent, SetNpcStatus, AcquireItem, StartBattle, BattleAction, AdvanceBattleTurn, SettleBattle { String type(); }
    public record Rest() implements ApAction { public String type() { return "rest"; } }
    public record ConsumeRation() implements ApAction { public String type() { return "consume_ration"; } }
    public record UsePotion() implements ApAction { public String type() { return "use_potion"; } }
    public record AdvanceDay() implements ApAction { public String type() { return "advance_day"; } }
    public record GrantExperience(long amount) implements ApAction { public String type() { return "grant_experience"; } }
    public record LearnAbility(String abilityId) implements ApAction { public String type() { return "learn_ability"; } }
    public record EquipItem(String itemName, String slot) implements ApAction { public String type() { return "equip_item"; } }
    public record UseItem(String itemName) implements ApAction { public String type() { return "use_item"; } }
    public record ClaimQuestReward(String questId, long xp, long copper, long rations, long potions) implements ApAction { public String type() { return "claim_quest_reward"; } }
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
    public record StartBattle(String encounterName, String region, int enemyCount, long seed) implements ApAction { public String type() { return "start_battle"; } }
    public record BattleAction(ApBattle.ActionKind action, String targetId, String abilityId, String itemName) implements ApAction { public String type() { return "battle_action"; } }
    public record AdvanceBattleTurn() implements ApAction { public String type() { return "advance_battle_turn"; } }
    public record SettleBattle(ApBattle.Result result) implements ApAction { public String type() { return "settle_battle"; } }
    public record ApEvent(String type, long amount, int levelsGained) { public ApEvent(String type, long amount) { this(type, amount, 0); } }
    public record ApProjection(ApState state) {}
}
