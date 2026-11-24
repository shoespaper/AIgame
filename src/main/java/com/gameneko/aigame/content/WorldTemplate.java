package com.gameneko.aigame.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 保存可复用的叙事素材，以及由对应引擎解释的注册提案。 */
public record WorldTemplate(String id, String type, List<String> tags, String name, JsonNode materials,
                            JsonNode registrations) {
    /** 创建待补充的模板元数据。
     * @param type 世界类型标识
     * @param name 模板展示名称
     */
    public static WorldTemplate empty(String type, String name) {
        return new WorldTemplate(type, type, List.of(), name, JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode());
    }

    /** 按新模板身份复制世界素材。
     * @param id 新模板标识
     * @param type 世界类型
     * @param name 新模板名称
     * @param tags 展示标签
     */
    public WorldTemplate copyAs(String id, String type, String name, List<String> tags) {
        ObjectNode copy = materials.deepCopy();
        copy.put("template_id", id).put("world_type", type).put("name", name);
        return new WorldTemplate(id, type, List.copyOf(tags), name, copy, registrations.deepCopy());
    }
}
