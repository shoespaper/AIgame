package com.gameneko.aigame.content;

import java.util.List;
import java.util.Map;

/** 可复用的世界机制骨架，与具体世界书的叙事内容独立保存。 */
public record WorldTemplate(
        String id,
        String type,
        List<String> tags,
        String name,
        String contentVersion,
        long defaultThreatInterval,
        Map<String, RegionDefinition> regions,
        Map<String, WorldDefinition.DifficultyDefinition> difficulties,
        Map<String, WorldDefinition.TalentDefinition> talents,
        Map<String, WorldDefinition.AbilityDefinition> abilities,
        Map<String, WorldDefinition.MonsterDefinition> monsters,
        Map<String, WorldDefinition.NpcTemplate> npcTypes,
        WorldDefinition.EventTemplates eventTemplates,
        Map<String, WorldDefinition.ItemTemplate> itemTemplates,
        Map<String, Object> skillTypes,
        Map<String, Object> spellTypes,
        List<Object> questTemplates,
        List<Object> questFeatures,
        Map<String, Object> modifierPool,
        Map<String, Object> abilitySlots,
        Map<String, Object> creationDefaults) {

    /** 创建模板并复制集合，避免运行时内容被外部修改。
     * @param id 模板稳定标识
     * @param type 世界类型稳定标识
     * @param tags 模板分类标签
     * @param name 模板展示名称
     * @param contentVersion 内容版本
     * @param defaultThreatInterval 默认世界威胁推进间隔，单位为游戏日
     * @param regions 区域描述与怪物类型池
     * @param difficulties 难度预设
     * @param talents 天赋模板
     * @param abilities 已解析的能力模板
     * @param monsters 怪物模板
     * @param npcTypes 自包含性格和关系档位的 NPC 身份模板
     * @param eventTemplates 世界事件模板
     * @param itemTemplates 物品模板
     * @param skillTypes 技能类型骨架
     * @param spellTypes 法术类型骨架
     * @param questTemplates 任务模板
     * @param questFeatures 任务抽象特征
     * @param modifierPool 词缀完整定义
     * @param abilitySlots 能力槽骨架
     * @param creationDefaults 世界构建对话的可复用默认设定
     */
    public WorldTemplate {
        if (id == null || id.isBlank() || type == null || type.isBlank()) {
            throw new IllegalArgumentException("Template id and type are required");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        regions = regions == null ? Map.of() : Map.copyOf(regions);
        difficulties = difficulties == null ? Map.of() : Map.copyOf(difficulties);
        talents = talents == null ? Map.of() : Map.copyOf(talents);
        abilities = abilities == null ? Map.of() : Map.copyOf(abilities);
        monsters = monsters == null ? Map.of() : Map.copyOf(monsters);
        npcTypes = npcTypes == null ? Map.of() : Map.copyOf(npcTypes);
        eventTemplates = eventTemplates == null ? WorldDefinition.EventTemplates.empty() : eventTemplates;
        itemTemplates = itemTemplates == null ? Map.of() : Map.copyOf(itemTemplates);
        skillTypes = skillTypes == null ? Map.of() : Map.copyOf(skillTypes);
        spellTypes = spellTypes == null ? Map.of() : Map.copyOf(spellTypes);
        questTemplates = questTemplates == null ? List.of() : List.copyOf(questTemplates);
        questFeatures = questFeatures == null ? List.of() : List.copyOf(questFeatures);
        modifierPool = modifierPool == null ? Map.of() : Map.copyOf(modifierPool);
        abilitySlots = abilitySlots == null ? Map.of() : Map.copyOf(abilitySlots);
        creationDefaults = creationDefaults == null ? Map.of() : Map.copyOf(creationDefaults);
    }

    /** 创建空白机制骨架，供尚无模板的世界类型使用。
     * @param type 世界类型稳定标识
     * @param name 模板名称
     * @return 不绑定任何具体引擎机制的模板
     */
    public static WorldTemplate empty(String type, String name) {
        return new WorldTemplate(type, type, List.of(), name, "1.0", 7, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), WorldDefinition.EventTemplates.empty(), Map.of(), Map.of(),
                Map.of(), List.of(), List.of(), Map.of(), Map.of(), Map.of());
    }

    /** 复制骨架并替换模板元数据及创建默认值。
     * @param id 新模板标识
     * @param type 世界类型
     * @param name 展示名称
     * @param tags 分类标签
     * @param defaults 世界构建对话可复用的默认配置
     * @return 独立的可复用模板
     */
    public WorldTemplate copyAs(String id, String type, String name, List<String> tags, Map<String, Object> defaults) {
        return new WorldTemplate(id, type, tags, name, contentVersion, defaultThreatInterval, regions, difficulties,
                talents, abilities, monsters, npcTypes, eventTemplates, itemTemplates, skillTypes, spellTypes,
                questTemplates, questFeatures, modifierPool, abilitySlots, defaults);
    }

    /** 模板区域定义，包含叙事提示和可生成的怪物类型池。 */
    public record RegionDefinition(String descriptionHint, List<String> monsterTypePool) {
        /** 复制区域集合并保证池不可变。
         * @param descriptionHint 区域展示提示
         * @param monsterTypePool 允许生成的怪物类型标识
         */
        public RegionDefinition {
            descriptionHint = descriptionHint == null ? "" : descriptionHint;
            monsterTypePool = monsterTypePool == null ? List.of() : List.copyOf(monsterTypePool);
        }
    }
}
