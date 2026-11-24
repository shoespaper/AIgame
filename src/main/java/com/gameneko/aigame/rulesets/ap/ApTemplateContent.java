package com.gameneko.aigame.rulesets.ap;

import com.fasterxml.jackson.databind.JsonNode;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.content.WorldDefinition.AbilityDefinition;
import com.gameneko.aigame.content.WorldDefinition.AbilityMechanic;
import com.gameneko.aigame.content.WorldDefinition.IntRange;
import com.gameneko.aigame.content.WorldDefinition.ItemTemplate;
import com.gameneko.aigame.content.WorldDefinition.MonsterDefinition;
import com.gameneko.aigame.content.WorldDefinition.NpcTemplate;
import com.gameneko.aigame.content.WorldDefinition.TalentDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将已登记的 AP 模板转换为可执行的能力、怪物及交互数据。 */
public final class ApTemplateContent {
    /** 创建应用本次世界素材和对应规则定义的 AP 运行时。
     * @param world 世界书的基础内容
     * @param materials 已确认的世界素材，未登记时为空节点
     * @param rules 按模板标识索引的已验证 AP 规则
     */
    public static ApGameEngineRuntime runtime(WorldDefinition world, JsonNode materials, JsonNode rules) {
        if (!materials.path("registered_engines").path("ap").asBoolean()) return new ApGameEngineRuntime(world);
        return new ApGameEngineRuntime(configure(world, materials, rules), materials, rules);
    }

    /** 将世界条目映射为本引擎的计算数据。
     * @param world 世界书基础内容
     * @param materials 已确认的叙事素材
     * @param rules 按标识索引的 AP 规则
     */
    public static WorldDefinition configure(WorldDefinition world, JsonNode materials, JsonNode rules) {
        Map<String, AbilityDefinition> abilities = new LinkedHashMap<>();
        Map<String, MonsterDefinition> monsters = new LinkedHashMap<>();
        Map<String, NpcTemplate> npcs = new LinkedHashMap<>();
        Map<String, TalentDefinition> talents = new LinkedHashMap<>();
        Map<String, ItemTemplate> items = new LinkedHashMap<>();
        Map<String, List<String>> regions = new LinkedHashMap<>();
        for (String group : List.of("skill_types", "spell_types", "monster_types", "npc_types", "talent_types", "item_types")) {
            for (JsonNode item : materials.path(group)) {
                String id = item.path("id").asText();
                String name = item.path("name").asText(id);
                String description = item.path("description").asText();
                JsonNode rule = rules.path(item.path("engines").path("ap").path("template_id").asText());
                JsonNode params = rule.path("parameters");
                switch (group) {
                    case "skill_types", "spell_types" -> abilities.put(id, ability(id, name, description, rule));
                    case "monster_types" -> monsters.put(id, new MonsterDefinition(id, fixed(1), fixed(Math.max(1, params.path("max_hp").asInt())),
                            fixed(params.path("attack_power").asInt()), fixed(0), fixed(6), 4, fixed(10), fixed(1), "", List.of(), 1));
                    case "npc_types" -> npcs.put(id, new NpcTemplate(id, List.of(description), "", 0, false, false, false, false, false));
                    case "talent_types" -> talents.put(id, new TalentDefinition(name, description));
                    case "item_types" -> items.put(id + ":common", new ItemTemplate(id, "common", fixed(1),
                            params.path("healing").asInt(), 0, false, 0, 0));
                    default -> throw new IllegalArgumentException("未知素材类别：" + group);
                }
            }
        }
        for (JsonNode region : materials.path("regions")) {
            List<String> pool = new ArrayList<>();
            region.path("monster_ids").forEach(id -> pool.add(id.asText()));
            regions.put(region.path("id").asText(), pool);
        }
        return new WorldDefinition(world.worldId(), world.contentVersion(), world.nameHint(),
                world.descriptionHint(), world.defaultThreatInterval(), regions, Map.of(), talents, abilities, monsters,
                npcs, world.eventTemplates(), items);
    }

    /** 创建固定值的属性区间。
     * @param value 固定的非负属性数值
     */
    private static IntRange fixed(int value) { return new IntRange(value, value); }

    /** 按已注册的规则类型构造具体技能的可执行定义。
     * @param id 本局技能实例标识
     * @param name AI 创作的展示名称
     * @param description 技能的叙事描述
     * @param rule 已验证的 AP 技能或法术模板
     */
    static AbilityDefinition ability(String id, String name, String description, JsonNode rule) {
        JsonNode p = rule.path("parameters");
        AbilityMechanic.Category category = switch (rule.path("rule_type").asText()) {
            case "single_target_damage", "spell_damage" -> AbilityMechanic.Category.DAMAGE;
            case "self_heal" -> AbilityMechanic.Category.SELF_HEAL;
            case "defend" -> AbilityMechanic.Category.SELF_BUFF;
            default -> throw new IllegalArgumentException("无效的 AP 技能模板");
        };
        AbilityMechanic mechanic = new AbilityMechanic(category, "", 0, 1, p.path("power").asInt(), 100,
                p.path("healing").asInt(), "guard", p.path("guard").asInt(), 1, 0, 0, 0, false, false, "active");
        return new AbilityDefinition(id, rule.path("entity_type").asText(), 1, p.path("ap_cost").asInt(),
                p.path("sp_cost").asInt(), p.path("power").asInt(), Map.of(), name, description, description, mechanic);
    }
}
