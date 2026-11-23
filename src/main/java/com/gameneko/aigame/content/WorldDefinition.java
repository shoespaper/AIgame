package com.gameneko.aigame.content;

import com.gameneko.aigame.engine.WorldEffects;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 保存世界书内容及可供规则引擎读取的模板机制数据。 */
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

    /** 返回世界书拥有的共享角色字段和世界开场字段 schema。
     * @return 世界书共享初始化字段定义
     */
    public InitializationSchema initializationSchema() {
        var fields = new java.util.ArrayList<InitializationField>(List.of(
                new InitializationField("characterName", "角色名字", InitializationField.ValueType.TEXT,
                        true, List.of(), null, null, "请输入主角名字"),
                new InitializationField("age", "年龄", InitializationField.ValueType.INTEGER,
                        true, List.of(), 1L, 120L, "请输入主角年龄"),
                new InitializationField("gender", "性别", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "你的角色是什么性别？不想指定可以跳过"),
                new InitializationField("name", "世界名称", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "这个世界叫什么名字？"),
                new InitializationField("description", "世界描述", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "如何概括这个世界的背景？"),
                new InitializationField("location", "开场地点", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "故事从哪里开始？"),
                new InitializationField("region", "区域", InitializationField.ValueType.TEXT,
                        false, regionMonsterTypes.keySet().stream().sorted().toList(), null, null, "开场地点属于哪个区域？"),
                new InitializationField("time", "时间", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "故事从一天中的什么时候开始？"),
                new InitializationField("mainQuest", "主线方向", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "角色目前有什么主要目标？"),
                new InitializationField("originScene", "开场场景", InitializationField.ValueType.TEXT,
                        false, List.of(), null, null, "还有什么开场设定想补充？")));
        return new InitializationSchema(fields);
    }

    /** 返回世界书共享必填角色字段的兼容默认值。
     * @return 类型化的世界书默认字段值
     */
    public Map<String, com.gameneko.aigame.engine.InitializationValue> defaultInitializationAnswers() {
        return Map.of("characterName", new com.gameneko.aigame.engine.InitializationValue.Text("旅人"),
                "age", new com.gameneko.aigame.engine.InitializationValue.IntegerNumber(16),
                "gender", new com.gameneko.aigame.engine.InitializationValue.Text("未定"));
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

    public record NpcTemplate(String id, List<String> personalities, List<BondDefinition> bondPool,
                              String scheduleTemplate, int relationshipBase, boolean canTrain, boolean canCombat,
                              boolean sellsItems, boolean givesQuests, boolean enemy) {
        public NpcTemplate {
            personalities = personalities == null ? List.of() : List.copyOf(personalities);
            bondPool = bondPool == null ? List.of() : List.copyOf(bondPool);
            relationshipBase = Math.max(-100, Math.min(100, relationshipBase));
        }

        /** 兼容旧调用方的 NPC 模板构造器。
         * @param id NPC 身份标识
         * @param personalities 性格名称池
         * @param scheduleTemplate 日程模板标识
         * @param relationshipBase 默认关系值
         * @param canTrain 是否可训练
         * @param canCombat 是否可参与战斗
         * @param sellsItems 是否出售物品
         * @param givesQuests 是否发布任务
         * @param enemy 是否为敌对身份
         */
        public NpcTemplate(String id, List<String> personalities, String scheduleTemplate,
                           int relationshipBase, boolean canTrain, boolean canCombat,
                           boolean sellsItems, boolean givesQuests, boolean enemy) {
            this(id, personalities, List.of(), scheduleTemplate, relationshipBase, canTrain,
                    canCombat, sellsItems, givesQuests, enemy);
        }
    }

    /** NPC 关系档位及其叙事阈值。 */
    public record BondDefinition(String label, int minimum, String description) {
        /** 规范化关系档位。
         * @param label 档位名称
         * @param minimum 触发该档位的最低关系值
         * @param description 档位叙事提示
         */
        public BondDefinition {
            label = label == null ? "" : label;
            description = description == null ? "" : description;
            minimum = Math.max(-100, Math.min(100, minimum));
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
