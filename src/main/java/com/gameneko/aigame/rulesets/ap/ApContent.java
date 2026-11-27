package com.gameneko.aigame.rulesets.ap;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/** 保存由已登记模板解码的 AP 专用素材。
 * @param talents 按世界素材标识索引的天赋
 * @param abilities 按世界素材标识索引的技能和法术
 * @param monsters 按世界素材标识索引的怪物
 * @param npcTypes 按世界素材标识索引的 NPC
 * @param itemTemplates 按素材与品质索引的道具
 */
public record ApContent(Map<String, TalentDefinition> talents, Map<String, AbilityDefinition> abilities,
                        Map<String, MonsterDefinition> monsters, Map<String, NpcTemplate> npcTypes,
                        Map<String, ItemTemplate> itemTemplates) {
    /** 复制各素材索引，确保一轮结算读取同一份定义。 */
    public ApContent {
        talents = Map.copyOf(talents);
        abilities = Map.copyOf(abilities);
        monsters = Map.copyOf(monsters);
        npcTypes = Map.copyOf(npcTypes);
        itemTemplates = Map.copyOf(itemTemplates);
    }

    /** 返回加载表单时使用的空素材。 */
    static ApContent empty() {
        return new ApContent(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    /** 定义 AP 难度的初始资源、恢复、威胁和战斗倍率。
     * @param threatInterval 威胁升级间隔，单位为天
     * @param startingCopper 初始铜币
     * @param startingRations 初始口粮份数
     * @param startingPotions 初始药水瓶数
     * @param hpRecoveryBonus 每日额外恢复的生命点数
     * @param label 难度展示名称
     * @param description 难度叙事说明
     * @param enemyHpMultiplierPercent 敌方生命倍率，以百分比表示
     * @param enemyPowerMultiplierPercent 敌方攻击倍率，以百分比表示
     * @param enemyApMultiplierPercent 敌方行动点倍率，以百分比表示
     * @param experienceMultiplierPercent 经验奖励倍率，以百分比表示
     * @param goldMultiplierPercent 铜币奖励倍率，以百分比表示
     */
    record DifficultyDefinition(long threatInterval, long startingCopper, long startingRations,
                                long startingPotions, int hpRecoveryBonus, String label, String description,
                                int enemyHpMultiplierPercent, int enemyPowerMultiplierPercent, int enemyApMultiplierPercent,
                                int experienceMultiplierPercent, int goldMultiplierPercent) {}

    /** 保存 AP 可授予角色的天赋资料。
     * @param name 天赋名称
     * @param description 天赋的叙事说明
     */
    public record TalentDefinition(String name, String description) {}

    /** 保存 AP 学习与结算使用的能力定义。
     * @param id 世界素材或角色能力实例标识
     * @param kind 能力类别，skill 或 spell
     * @param tier 能力等级
     * @param apCost 行动点消耗
     * @param spCost 法力消耗
     * @param power 能力威力
     * @param prerequisites 按属性名索引的最低学习要求
     * @param name 展示名称
     * @param effectHint 效果提示
     * @param description 叙事说明
     * @param mechanic 已解码的 AP 效果
     */
    public record AbilityDefinition(String id, String kind, int tier, int apCost, int spCost, int power,
                                    Map<String, Integer> prerequisites, String name, String effectHint,
                                    String description, AbilityMechanic mechanic) {
        /** 复制学习要求。 */
        public AbilityDefinition { prerequisites = Map.copyOf(prerequisites); }
    }

    /** 保存 AP 战斗能力的计算参数。
     * @param category AP 结算类别
     * @param effect 附加状态效果标识，可为空字符串
     * @param amount 附加状态数值
     * @param duration 效果持续轮数
     * @param power 模板基础威力
     * @param powerMultiplierPercent 威力倍率，以百分比表示
     * @param healAmount 治疗量，单位为生命点
     * @param buffStat 强化属性名，可为空字符串
     * @param buffAmount 强化数值
     * @param oncePerCombat 是否每场战斗仅可使用一次
     * @param clearStatuses 是否清除状态
     * @param activation 能力的激活方式
     */
    public record AbilityMechanic(Category category, String effect, int amount, int duration, int power,
                                  int powerMultiplierPercent, int healAmount, String buffStat, int buffAmount,
                                  boolean oncePerCombat, boolean clearStatuses, String activation) {
        public enum Category { ATTACK_MODIFIER, DEBUFF, SELF_HEAL, DAMAGE, DAMAGE_DEBUFF, HEAL, SELF_BUFF, DOT }
    }

    /** 保存生成 AP 普通攻击型怪物需要的数据。
     * @param id 怪物素材标识
     * @param name 怪物展示名称
     * @param maxHp 未应用难度倍率的生命上限
     * @param attackPower 未应用难度倍率的普通攻击威力
     */
    public record MonsterDefinition(String id, String name, int maxHp, int attackPower) {}

    /** 保存 AP 创建 NPC 时使用的状态定义。
     * @param personalities 可选性格描述
     * @param scheduleTemplate 日程描述，可为空字符串
     * @param relationshipBase 初始关系值
     * @param canTrain 是否可提供训练
     * @param canCombat 是否可进入战斗
     * @param sellsItems 是否可出售物品
     * @param givesQuests 是否可提供任务
     */
    public record NpcTemplate(List<String> personalities, String scheduleTemplate, int relationshipBase,
                              boolean canTrain, boolean canCombat, boolean sellsItems, boolean givesQuests) {
        /** 复制性格描述列表。 */
        public NpcTemplate { personalities = List.copyOf(personalities); }
    }

    /** 保存 AP 道具的使用效果与价值。
     * @param valueRange 基础价值区间
     * @param hpRecovery 生命恢复点数
     * @param spRecovery 法力恢复点数
     * @param clearsStatuses 是否清除状态
     * @param weaponDamageBonus 武器伤害加成
     * @param armorMitigationBonus 防具减伤加成
     */
    public record ItemTemplate(IntRange valueRange, int hpRecovery, int spRecovery, boolean clearsStatuses,
                               int weaponDamageBonus, int armorMitigationBonus) {}

    /** 保存含首尾的非负整数区间。
     * @param minimum 最小值
     * @param maximum 最大值
     */
    public record IntRange(int minimum, int maximum) {
        /** 校验区间的边界。 */
        public IntRange {
            if (minimum < 0 || maximum < minimum) throw new IllegalArgumentException("Invalid integer range");
        }

        /** 在区间内抽取一个整数。
         * @param random 用于生成抽样值的随机源
         */
        int roll(RandomGenerator random) {
            return minimum == maximum ? minimum : (int) random.nextLong(minimum, (long) maximum + 1);
        }
    }
}
