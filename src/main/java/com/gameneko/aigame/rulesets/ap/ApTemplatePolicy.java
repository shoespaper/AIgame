package com.gameneko.aigame.rulesets.ap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.engine.EngineTemplatePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** 持有 AP 的预置规则，并校验 AP 提案中的实体类型、规则类型和整数参数。 */
public final class ApTemplatePolicy implements EngineTemplatePolicy {
    private static final Map<String, String> REFERENCE_TYPES = Map.of(
            "npc_types", "npc", "monster_types", "monster", "skill_types", "skill",
            "spell_types", "spell", "talent_types", "talent", "item_types", "item",
            "quest_templates", "quest", "event_templates", "event");

    private final JsonNode defaults;

    /** 加载 AP 自身的规则模板和参数示例。
     * @param mapper 读取引擎规则 JSON 的编解码器
     */
    public ApTemplatePolicy(ObjectMapper mapper) throws IOException {
        try (InputStream stream = ApTemplatePolicy.class.getResourceAsStream("/rulesets/ap/template-rules.json")) {
            if (stream == null) throw new IOException("缺少 AP 规则模板资源");
            defaults = mapper.readTree(stream);
        }
    }

    /** 返回 AP 引擎的稳定标识。 */
    @Override
    public String id() {
        return "ap";
    }

    /** 返回一份 AP 预置规则数组，以免调用方修改校验依据。 */
    @Override
    public JsonNode defaults() {
        return defaults.deepCopy();
    }

    /** 返回 AP 素材池对应的规则实体类型。 */
    @Override
    public Map<String, String> referenceTypes() {
        return REFERENCE_TYPES;
    }

    /** 按 AP 规则的类型与参数结构校验新定义。
     * @param rule 含有 AP 实体类型、规则类型和参数的待注册 JSON 对象
     */
    @Override
    public void validateRule(JsonNode rule) {
        String id = rule.path("template_id").asText();
        for (JsonNode example : defaults) {
            if (!example.path("entity_type").equals(rule.path("entity_type"))
                    || !example.path("rule_type").equals(rule.path("rule_type"))) continue;
            JsonNode schema = example.path("parameters");
            JsonNode parameters = rule.path("parameters");
            if (!parameters.isObject() || parameters.size() != schema.size()) {
                throw new IllegalArgumentException("AP 规则 " + id + " 的 parameters 字段须与示例一致：" + schema);
            }
            for (Map.Entry<String, JsonNode> field : schema.properties()) {
                JsonNode value = parameters.path(field.getKey());
                int minimum = "max_hp".equals(field.getKey()) ? 1 : 0;
                if (!value.isInt() || value.intValue() < minimum || value.intValue() > 1000) {
                    throw new IllegalArgumentException("AP 规则 " + id + " 的 parameters." + field.getKey()
                            + " 必须是 " + minimum + " 至 1000 的整数");
                }
            }
            return;
        }
        throw new IllegalArgumentException("AP 规则 " + id + " 的 entity_type 或 rule_type 与规则示例不匹配");
    }

    /** 核对素材引用的规则存在且类别符合 AP 素材池的要求。
     * @param group 该素材所属的 AP 素材池标识
     * @param itemId 用于错误定位的素材标识
     * @param rule 按精确标识找到的 AP 规则，定义缺失时为缺失节点
     */
    @Override
    public void validateReference(String group, String itemId, JsonNode rule) {
        String expectedType = REFERENCE_TYPES.get(group);
        if (expectedType == null || !expectedType.equals(rule.path("entity_type").asText())) {
            throw new IllegalArgumentException(group + "." + itemId + " 引用的 AP 规则 "
                    + rule.path("template_id").asText() + " 类别错误，期望 " + expectedType
                    + "，实际 " + rule.path("entity_type").asText());
        }
    }
}
