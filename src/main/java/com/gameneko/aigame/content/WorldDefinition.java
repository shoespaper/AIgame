package com.gameneko.aigame.content;

import com.gameneko.aigame.engine.WorldEffects;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only projection of the static world book used by initialization and AP validation. */
public record WorldDefinition(String worldId, String contentVersion, String nameHint,
                              String descriptionHint, long defaultThreatInterval,
                              Map<String, List<String>> regionMonsterTypes,
                               Map<String, DifficultyDefinition> difficulties,
                               Map<String, TalentDefinition> talents,
                               Map<String, AbilityDefinition> abilities,
                               Map<String, MonsterDefinition> monsters,
                               Map<String, NpcTemplate> npcTypes,
                               EventTemplates eventTemplates,
                               Map<String, ItemTemplate> itemTemplates) {
    public WorldDefinition {
        regionMonsterTypes = regionMonsterTypes.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        difficulties = Map.copyOf(difficulties);
        talents = Map.copyOf(talents);
        abilities = Map.copyOf(abilities);
        monsters = Map.copyOf(monsters);
        npcTypes = Map.copyOf(npcTypes);
        eventTemplates = eventTemplates == null ? EventTemplates.empty() : eventTemplates;
        itemTemplates = itemTemplates == null ? Map.of() : Map.copyOf(itemTemplates);
    }

    /** Initial fields declared by the world book; engine-specific fields are composed separately. */
    public InitializationSchema initializationSchema() {
        return new InitializationSchema(List.of(
                new InitializationField("characterName", "角色名字", InitializationField.ValueType.TEXT,
                        true, List.of(), null, null, "请输入主角名字"),
                new InitializationField("age", "年龄", InitializationField.ValueType.INTEGER,
                        true, List.of(), 1L, 120L, "请输入主角年龄"),
                // Gender is a common character field. "Undecided" remains valid for players
                // who do not want to specify it during setup.
                new InitializationField("gender", "性别", InitializationField.ValueType.TEXT,
                        false, List.of("未定"), null, null, "可选")));
    }

    public WorldDefinition(String worldId, String contentVersion, String nameHint,
                           String descriptionHint, long defaultThreatInterval,
                           Map<String, List<String>> regionMonsterTypes,
                           Map<String, DifficultyDefinition> difficulties,
                           Map<String, TalentDefinition> talents) {
        this(worldId, contentVersion, nameHint, descriptionHint, defaultThreatInterval,
                regionMonsterTypes, difficulties, talents, Map.of(), Map.of(), Map.of(), EventTemplates.empty(), Map.of());
    }

    public WorldDefinition(String worldId, String contentVersion, String nameHint,
                           String descriptionHint, long defaultThreatInterval,
                           Map<String, List<String>> regionMonsterTypes,
                           Map<String, DifficultyDefinition> difficulties,
                           Map<String, TalentDefinition> talents,
                           Map<String, AbilityDefinition> abilities) {
        this(worldId, contentVersion, nameHint, descriptionHint, defaultThreatInterval,
                regionMonsterTypes, difficulties, talents, abilities, Map.of(), Map.of(), EventTemplates.empty(), Map.of());
    }

    public record DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                       long startingPotions, int hpRecoveryBonus, String label, String description,
                                       int enemyHpMultiplierPercent, int enemyPowerMultiplierPercent,
                                       int enemyApMultiplierPercent, int enemyMitigationMultiplierPercent,
                                       int experienceMultiplierPercent, int goldMultiplierPercent,
                                       int lootLuckyBonus) {
        public DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                    long startingPotions, String label, String description) {
            this(threatInterval, startingCopper, startingRations, startingPotions, 0, label, description,
                    100, 100, 100, 100, 100, 100, 0);
        }

        public DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                    long startingPotions, int hpRecoveryBonus, String label, String description) {
            this(threatInterval, startingCopper, startingRations, startingPotions, hpRecoveryBonus, label,
                    description, 100, 100, 100, 100, 100, 100, 0);
        }
    }

    public record TalentDefinition(String name, String description) {}

    public record AbilityDefinition(String id, String kind, int tier, int apCost, int spCost,
                                     int power, Map<String, Integer> prerequisites,
                                     String name, String effectHint, String description,
                                     AbilityMechanic mechanic) {
        public AbilityDefinition {
            prerequisites = Map.copyOf(prerequisites);
            mechanic = mechanic == null ? AbilityMechanic.none() : mechanic;
        }

        public AbilityDefinition(String id, String kind, int tier, int apCost, int spCost,
                                 int power, Map<String, Integer> prerequisites,
                                 String name, String effectHint, String description) {
            this(id, kind, tier, apCost, spCost, power, prerequisites, name, effectHint, description,
                    AbilityMechanic.none());
        }
    }

    public record AbilityMechanic(Category category, String effect, int amount, int duration,
                                  int power, int powerMultiplierPercent, int healAmount,
                                  String buffStat, int buffAmount, int targetCount,
                                  int extraPerTurn, int costPenalty, int counterPowerPercent,
                                   boolean oncePerCombat, boolean clearStatuses, String activation) {
        public enum Category {
            NONE, ATTACK_MODIFIER, DEFEND_BONUS, MULTI_ATTACK, ACTION_COST_OVERRIDE,
            STAT_MODIFIER, DEFEND_REACTION, EXTRA_ACTION, ATTACK_BONUS, DEBUFF,
            SELF_HEAL, INFO_REVEAL, DAMAGE, DAMAGE_DEBUFF, HEAL, SELF_BUFF, UTILITY, DOT
        }

        public AbilityMechanic {
            category = category == null ? Category.NONE : category;
            effect = effect == null ? "" : effect;
            buffStat = buffStat == null ? "" : buffStat;
            activation = activation == null ? "" : activation;
            if (amount < 0 || duration < 0 || power < 0 || healAmount < 0 || buffAmount < 0
                    || targetCount < 0 || extraPerTurn < 0 || costPenalty < 0) {
                throw new IllegalArgumentException("Ability mechanic values must not be negative");
            }
        }

        public static AbilityMechanic none() {
            return new AbilityMechanic(Category.NONE, "", 0, 0, 0, 100, 0,
                    "", 0, 0, 0, 0, 0, false, false, "");
        }
    }

    public record IntRange(int minimum, int maximum) {
        public IntRange {
            if (minimum < 0 || maximum < minimum) throw new IllegalArgumentException("Invalid integer range");
        }

        public int roll(java.util.random.RandomGenerator random) {
            return minimum == maximum ? minimum : random.nextInt(minimum, maximum + 1);
        }
    }

    public record MonsterDefinition(String id, IntRange challengeRange, IntRange hpRange,
                                    IntRange powerRange, IntRange mitigationRange, IntRange speedRange,
                                    int actionPoints, IntRange experienceRange, IntRange lootCopperRange,
                                    String behavior, List<String> abilityPool, double speedMultiplier) {
        public MonsterDefinition {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Monster id is required");
            abilityPool = abilityPool == null ? List.of() : List.copyOf(abilityPool);
            if (speedMultiplier <= 0) throw new IllegalArgumentException("Monster speed multiplier must be positive");
            if (actionPoints <= 0) throw new IllegalArgumentException("Monster AP must be positive");
        }
    }

    public record NpcTemplate(String id, List<String> personalities, String scheduleTemplate,
                              int relationshipBase, boolean canTrain, boolean canCombat,
                              boolean sellsItems, boolean givesQuests, boolean enemy) {
        public NpcTemplate {
            personalities = personalities == null ? List.of() : List.copyOf(personalities);
            relationshipBase = Math.max(-100, Math.min(100, relationshipBase));
        }
    }

    public record EventTemplate(String id, String type, String category, int weight,
                                boolean repeating, int level, WorldEffects effects, String description) {
        public EventTemplate {
            weight = Math.max(1, weight);
            effects = effects == null ? WorldEffects.none() : effects;
            description = description == null ? "" : description;
        }

        public EventTemplate(String id, String type, String category, int weight,
                             boolean repeating, WorldEffects effects, String description) {
            this(id, type, category, weight, repeating, 0, effects, description);
        }
    }

    public record EventTemplates(List<EventTemplate> timed, List<EventTemplate> incidental) {
        public EventTemplates {
            timed = timed == null ? List.of() : List.copyOf(timed);
            incidental = incidental == null ? List.of() : List.copyOf(incidental);
        }

        public static EventTemplates empty() { return new EventTemplates(List.of(), List.of()); }
    }

    public record ItemTemplate(String category, String tier, IntRange valueRange,
                               int hpRecovery, int spRecovery, boolean clearsStatuses,
                               int weaponDamageBonus, int armorMitigationBonus) {
        public ItemTemplate {
            if (category == null || category.isBlank() || tier == null || tier.isBlank()) throw new IllegalArgumentException("Item template identity is required");
            if (valueRange == null || hpRecovery < 0 || spRecovery < 0 || weaponDamageBonus < 0 || armorMitigationBonus < 0) throw new IllegalArgumentException("Invalid item template");
        }

        public String key() { return category + ":" + tier; }
    }
}
