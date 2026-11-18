package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.ActionValidation;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.RulesetResolution;
import com.gameneko.aigame.engine.RulesetRuntime;

import java.util.List;
import java.util.Map;

/** AP baseline runtime with typed actions for the first state transitions. */
public final class ApRulesetRuntime implements RulesetRuntime<ApRulesetRuntime.ApState, ApRulesetRuntime.ApAction,
        ApRulesetRuntime.ApEvent, ApRulesetRuntime.ApProjection> {
    private static final Map<String, Archetype> ARCHETYPES = Map.of(
            "swordsman", new Archetype(14, 12, 8, 12, 9, 9, "短剑", "+2", "皮背心"),
            "mage", new Archetype(7, 10, 16, 8, 14, 10, "学徒法杖", "+1", "法袍"),
            "ranger", new Archetype(11, 15, 10, 10, 12, 9, "短弓", "+1", "轻便布衣"),
            "alchemist", new Archetype(9, 10, 15, 9, 11, 13, "投石索", "+1", "工作围裙"));
    private static final Archetype DEFAULT = new Archetype(10, 10, 10, 10, 10, 10, "铁匕首", "+2", "旅人衣");

    @Override public String id() { return "ap"; }
    @Override public String version() { return "baseline-1"; }

    public ApState initialize(ApInitializationRequest request, WorldDefinition world) {
        if (request == null || world == null) throw new IllegalArgumentException("AP initialization input is required");
        String archetype = request.archetype() == null ? "swordsman" : request.archetype().toLowerCase().trim();
        Archetype preset = ARCHETYPES.getOrDefault(archetype, DEFAULT);
        String difficulty = request.difficulty() == null ? "adventure" : request.difficulty().toLowerCase().trim();
        WorldDefinition.DifficultyDefinition diff = world.difficulties().get(difficulty);
        if (diff == null) throw new IllegalArgumentException("Unknown difficulty: " + difficulty);
        int hp = 20 + preset.constitution();
        int sp = 6 + preset.intelligence() / 2;
        Talent talent = world.talents().entrySet().stream().findFirst()
                .map(entry -> new Talent(entry.getKey(), entry.getValue().name(), entry.getValue().description(), false))
                .orElse(null);
        return new ApState(request.characterName(), request.age(), request.gender(), archetype, difficulty,
                1, 0, 100, preset.strength(), preset.dexterity(), preset.constitution(), preset.intelligence(),
                preset.wisdom(), preset.charisma(), hp, hp, sp, sp,
                List.of(new Item(preset.weapon(), "weapon", preset.damage(), 1), new Item(preset.armor(), "armor", "", 1)),
                new Equipment(preset.weapon(), preset.armor()),
                new Resources(diff.startingCopper(), diff.startingRations(), diff.startingPotions()), talent);
    }

    @Override
    public ActionValidation validate(ApState state, ApAction action) {
        return validate(state, action, null);
    }

    public ActionValidation validate(ApState state, ApAction action, WorldDefinition world) {
        if (state == null || action == null) return ActionValidation.reject("State and action are required");
        if (state.hpCurrent() <= 0) return ActionValidation.reject("Character is not alive");
        if (action instanceof LearnAbility learn) {
            if (world == null || !world.abilities().containsKey(learn.abilityId())) return ActionValidation.reject("Unknown ability");
            if (state.hasAbility(learn.abilityId())) return ActionValidation.reject("Ability already learned");
            var definition = world.abilities().get(learn.abilityId());
            for (var requirement : definition.prerequisites().entrySet()) {
                long actual = state.attribute(requirement.getKey());
                if (actual < requirement.getValue()) return ActionValidation.reject("Prerequisite not met: " + requirement.getKey());
            }
            return ActionValidation.accept();
        }
        if (action instanceof EquipItem equip) {
            return state.inventory().stream().anyMatch(item -> item.name().equals(equip.itemName())
                    && item.type().equals(equip.slot())) ? ActionValidation.accept()
                    : ActionValidation.reject("Item is not available for that equipment slot");
        }
        if (action instanceof UseItem use) {
            return isPotion(use.itemName()) && state.resources().potions() > 0 && state.hpCurrent() < state.hpMax()
                    ? ActionValidation.accept() : ActionValidation.reject("Potion cannot be used");
        }
        if (action instanceof ClaimQuestReward reward) {
            if (reward.questId() == null || reward.questId().isBlank()) return ActionValidation.reject("Quest id is required");
            if (state.quests().stream().anyMatch(quest -> quest.questId().equals(reward.questId()))) return ActionValidation.reject("Quest reward already claimed");
            if (reward.xp() < 0 || reward.copper() < 0 || reward.rations() < 0 || reward.potions() < 0
                    || reward.xp() + reward.copper() + reward.rations() + reward.potions() <= 0) return ActionValidation.reject("Quest reward must be positive");
            return ActionValidation.accept();
        }
        if (action instanceof GrantExperience grant) {
            return grant.amount() > 0 ? ActionValidation.accept() : ActionValidation.reject("Experience must be positive");
        }
        if (action instanceof Rest) return ActionValidation.accept();
        if (action instanceof AdvanceDay) return ActionValidation.accept();
        if (action instanceof ConsumeRation) {
            return state.resources().rations() > 0
                    ? ActionValidation.accept() : ActionValidation.reject("Not enough rations");
        }
        if (action instanceof UsePotion) {
            if (state.resources().potions() <= 0) return ActionValidation.reject("Not enough potions");
            return state.hpCurrent() < state.hpMax()
                    ? ActionValidation.accept() : ActionValidation.reject("HP is already full");
        }
        return ActionValidation.reject("Unsupported AP action");
    }

    @Override
    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action) {
        return resolve(state, action, 0, null);
    }

    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action, int hpRecoveryBonus) {
        return resolve(state, action, hpRecoveryBonus, null);
    }

    public RulesetResolution<ApState, ApEvent> resolve(ApState state, ApAction action, int hpRecoveryBonus, WorldDefinition world) {
        ActionValidation validation = validate(state, action, world);
        if (!validation.accepted()) throw new IllegalArgumentException(validation.reason());
        if (action instanceof LearnAbility learn) {
            var definition = world.abilities().get(learn.abilityId());
            var ability = new Ability(definition.id(), definition.kind(), definition.tier(), definition.apCost(),
                    definition.spCost(), definition.power(), definition.name(), definition.effectHint(), definition.description());
            ApState next = "spell".equals(definition.kind())
                    ? state.withSpellsAdd(ability) : state.withSkillsAdd(ability);
            return result(next, new ApEvent("learn_ability", 1));
        }
        if (action instanceof EquipItem equip) {
            Equipment nextEquipment = "weapon".equals(equip.slot())
                    ? state.equipment().withWeapon(equip.itemName()) : state.equipment().withArmor(equip.itemName());
            return result(state.withEquipment(nextEquipment), new ApEvent("equip_item", 1));
        }
        if (action instanceof UseItem) {
            int hp = Math.min(state.hpMax(), state.hpCurrent() + 10);
            return result(state.withVitality(hp, state.spCurrent())
                    .withResources(state.resources().withPotions(state.resources().potions() - 1)), new ApEvent("use_item", hp - state.hpCurrent()));
        }
        if (action instanceof ClaimQuestReward reward) {
            ApState next = state.withResources(state.resources().add(reward.copper(), reward.rations(), reward.potions()))
                    .withQuestsAdd(new QuestReference(reward.questId()));
            if (reward.xp() > 0) next = applyExperience(next, reward.xp());
            return result(next, new ApEvent("quest_reward", reward.xp() + reward.copper() + reward.rations() + reward.potions()));
        }
        if (action instanceof GrantExperience grant) {
            ApState next = applyExperience(state, grant.amount());
            return result(next, new ApEvent("experience", grant.amount(), next.level() - state.level()));
        }
        if (action instanceof Rest) {
            int hp = Math.min(state.hpMax(), state.hpCurrent() + 2);
            int sp = Math.min(state.spMax(), state.spCurrent() + 2);
            return result(state.withVitality(hp, sp), new ApEvent("rest", 2));
        }
        if (action instanceof ConsumeRation) {
            return result(state.withResources(state.resources().withRations(state.resources().rations() - 1)),
                    new ApEvent("consume_ration", 1));
        }
        if (action instanceof UsePotion) {
            int hp = Math.min(state.hpMax(), state.hpCurrent() + 10);
            return result(state.withVitality(hp, state.spCurrent())
                            .withResources(state.resources().withPotions(state.resources().potions() - 1)),
                    new ApEvent("use_potion", hp - state.hpCurrent()));
        }
        if (action instanceof AdvanceDay) {
            long rations = state.resources().rations();
            int hpRecovery = Math.max(0, state.hpMax() / 4 + hpRecoveryBonus);
            int spRecovery = state.spMax() / 2;
            int hp = Math.min(state.hpMax(), state.hpCurrent() + hpRecovery);
            int sp = Math.min(state.spMax(), state.spCurrent() + spRecovery);
            ApState next = state.withVitality(hp, sp);
            next = next.withStatusEffects(tickStatusEffects(state.statusEffects()));
            if (rations > 0) {
                next = next.withResources(next.resources().withRations(rations - 1));
                return result(next, new ApEvent("advance_day", 1));
            }
            return result(next.withStatusEffect(new StatusEffect("饥饿", 1, "所有判定 -2")),
                    new ApEvent("advance_day_hunger", 1));
        }
        throw new IllegalArgumentException("Unsupported AP action");
    }

    private ApState applyExperience(ApState state, long amount) {
        long xp = Math.addExact(state.xp(), amount);
        int level = state.level();
        long xpToNext = state.xpToNext();
        while (xp >= xpToNext) {
            xp -= xpToNext;
            level = Math.addExact(level, 1);
            xpToNext = Math.multiplyExact(level, 100L);
        }
        return state.withProgression(level, xp, xpToNext);
    }

    private static boolean isPotion(String itemName) {
        return "potion".equalsIgnoreCase(itemName) || "药水".equals(itemName);
    }

    private List<StatusEffect> tickStatusEffects(List<StatusEffect> effects) {
        return effects.stream().filter(effect -> effect.duration() > 1)
                .map(effect -> new StatusEffect(effect.name(), effect.duration() - 1, effect.penalty())).toList();
    }

    private RulesetResolution<ApState, ApEvent> result(ApState state, ApEvent event) {
        return new RulesetResolution<>(state, List.of(event));
    }
    @Override public ApProjection project(ApState state) { return new ApProjection(state); }

    private record Archetype(int strength, int dexterity, int intelligence, int constitution, int wisdom, int charisma,
                             String weapon, String damage, String armor) {}
    public record ApInitializationRequest(String characterName, int age, String gender, String archetype, String difficulty) {}
    public record ApState(String characterName, int age, String gender, String archetype, String difficulty,
                          int level, long xp, long xpToNext, int strength, int dexterity, int constitution,
                          int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent,
                          int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent,
                          List<StatusEffect> statusEffects, List<Ability> skills, List<Ability> spells,
                          List<QuestReference> quests)
            implements GameState {
        public ApState {
            inventory = inventory == null ? List.of() : List.copyOf(inventory);
            statusEffects = statusEffects == null ? List.of() : List.copyOf(statusEffects);
            skills = skills == null ? List.of() : List.copyOf(skills);
            spells = spells == null ? List.of() : List.copyOf(spells);
            quests = quests == null ? List.of() : List.copyOf(quests);
        }

        public ApState(String characterName, int age, String gender, String archetype, String difficulty,
                       int level, long xp, long xpToNext, int strength, int dexterity, int constitution,
                       int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent,
                       int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent) {
            this(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity,
                    constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax,
                    inventory, equipment, resources, uniqueTalent, List.of(), List.of(), List.of(), List.of());
        }

        public ApState(String characterName, int age, String gender, String archetype, String difficulty,
                       int level, long xp, long xpToNext, int strength, int dexterity, int constitution,
                       int intelligence, int wisdom, int charisma, int hpCurrent, int hpMax, int spCurrent,
                       int spMax, List<Item> inventory, Equipment equipment, Resources resources, Talent uniqueTalent,
                       List<StatusEffect> statusEffects) {
            this(characterName, age, gender, archetype, difficulty, level, xp, xpToNext, strength, dexterity,
                    constitution, intelligence, wisdom, charisma, hpCurrent, hpMax, spCurrent, spMax,
                    inventory, equipment, resources, uniqueTalent, statusEffects, List.of(), List.of(), List.of());
        }

        public ApState withVitality(int hp, int sp) {
            return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext,
                    strength, dexterity, constitution, intelligence, wisdom, charisma,
                    hp, hpMax, sp, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, quests);
        }

        public ApState withResources(Resources nextResources) {
            return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext,
                    strength, dexterity, constitution, intelligence, wisdom, charisma,
                    hpCurrent, hpMax, spCurrent, spMax, inventory, equipment, nextResources, uniqueTalent, statusEffects, skills, spells, quests);
        }

        public ApState withStatusEffect(StatusEffect effect) {
            List<StatusEffect> next = new java.util.ArrayList<>(statusEffects);
            next.add(effect);
            return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext,
                    strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax,
                    spCurrent, spMax, inventory, equipment, resources, uniqueTalent, next, skills, spells, quests);
        }

        public ApState withStatusEffects(List<StatusEffect> effects) {
            return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext,
                    strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax,
                    spCurrent, spMax, inventory, equipment, resources, uniqueTalent, effects, skills, spells, quests);
        }

        public ApState withProgression(int nextLevel, long nextXp, long nextXpToNext) {
            return new ApState(characterName, age, gender, archetype, difficulty, nextLevel, nextXp, nextXpToNext,
                    strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax,
                    spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects, skills, spells, quests);
        }

        public ApState withSkillsAdd(Ability ability) {
            List<Ability> next = new java.util.ArrayList<>(skills); next.add(ability);
            return withAbilities(next, spells, quests);
        }

        public ApState withSpellsAdd(Ability ability) {
            List<Ability> next = new java.util.ArrayList<>(spells); next.add(ability);
            return withAbilities(skills, next, quests);
        }

        public ApState withQuestsAdd(QuestReference quest) {
            List<QuestReference> next = new java.util.ArrayList<>(quests); next.add(quest);
            return withAbilities(skills, spells, next);
        }

        public ApState withEquipment(Equipment nextEquipment) {
            return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext,
                    strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax,
                    spCurrent, spMax, inventory, nextEquipment, resources, uniqueTalent, statusEffects, skills, spells, quests);
        }

        private ApState withAbilities(List<Ability> nextSkills, List<Ability> nextSpells, List<QuestReference> nextQuests) {
            return new ApState(characterName, age, gender, archetype, difficulty, level, xp, xpToNext,
                    strength, dexterity, constitution, intelligence, wisdom, charisma, hpCurrent, hpMax,
                    spCurrent, spMax, inventory, equipment, resources, uniqueTalent, statusEffects,
                    nextSkills, nextSpells, nextQuests);
        }

        public boolean hasAbility(String id) {
            return skills.stream().anyMatch(value -> value.id().equals(id)) || spells.stream().anyMatch(value -> value.id().equals(id));
        }

        public long attribute(String name) {
            return switch (name) {
                case "STR" -> strength;
                case "DEX" -> dexterity;
                case "CON" -> constitution;
                case "INT" -> intelligence;
                case "WIS" -> wisdom;
                case "CHA" -> charisma;
                case "level" -> level;
                default -> 0;
            };
        }
    }
    public record Item(String name, String type, String damage, int quantity) {}
    public record Equipment(String weapon, String armor) {
        public Equipment withWeapon(String value) { return new Equipment(value, armor); }
        public Equipment withArmor(String value) { return new Equipment(weapon, value); }
    }
    public record Resources(long copper, long rations, long potions) {
        public Resources {
            if (copper < 0 || rations < 0 || potions < 0) throw new IllegalArgumentException("Resources must not be negative");
        }
        public Resources withRations(long value) { return new Resources(copper, value, potions); }
        public Resources withPotions(long value) { return new Resources(copper, rations, value); }
        public Resources add(long copperDelta, long rationsDelta, long potionsDelta) {
            return new Resources(Math.addExact(copper, copperDelta), Math.addExact(rations, rationsDelta),
                    Math.addExact(potions, potionsDelta));
        }
    }
    public record Talent(String type, String name, String description, boolean awakened) {}
    public record StatusEffect(String name, int duration, String penalty) {
        public StatusEffect {
            if (duration <= 0) throw new IllegalArgumentException("Status duration must be positive");
        }
    }
    public record Ability(String id, String kind, int tier, int apCost, int spCost, int power,
                          String name, String effectHint, String description) {}
    public record QuestReference(String questId) {}
    public sealed interface ApAction permits Rest, ConsumeRation, UsePotion, AdvanceDay, GrantExperience,
            LearnAbility, EquipItem, UseItem, ClaimQuestReward {
        String type();
    }
    public record Rest() implements ApAction { @Override public String type() { return "rest"; } }
    public record ConsumeRation() implements ApAction { @Override public String type() { return "consume_ration"; } }
    public record UsePotion() implements ApAction { @Override public String type() { return "use_potion"; } }
    public record AdvanceDay() implements ApAction { @Override public String type() { return "advance_day"; } }
    public record GrantExperience(long amount) implements ApAction {
        @Override public String type() { return "grant_experience"; }
    }
    public record LearnAbility(String abilityId) implements ApAction {
        @Override public String type() { return "learn_ability"; }
    }
    public record EquipItem(String itemName, String slot) implements ApAction {
        @Override public String type() { return "equip_item"; }
    }
    public record UseItem(String itemName) implements ApAction {
        @Override public String type() { return "use_item"; }
    }
    public record ClaimQuestReward(String questId, long xp, long copper, long rations, long potions) implements ApAction {
        @Override public String type() { return "claim_quest_reward"; }
    }
    public record ApEvent(String type, long amount, int levelsGained) {
        public ApEvent(String type, long amount) { this(type, amount, 0); }
    }
    public record ApProjection(ApState state) {}
}
