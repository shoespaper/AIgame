package com.gameneko.aigame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.rulesets.ap.ApTemplateContent;
import com.gameneko.aigame.rulesets.ap.ApTemplatePolicy;

import java.io.IOException;
import java.io.UncheckedIOException;

/** 为底层 AP 规则测试组装已确认的异世界模板。 */
final class ApTemplateFixture {
    /** 加载素材并按模板引用建立 AP 规则视图。
     * @param catalog 测试使用的世界内容目录
     */
    static WorldDefinition confirmed(ContentCatalog catalog) {
        WorldTemplate template = catalog.template("isekai");
        ObjectNode rules = JsonNodeFactory.instance.objectNode();
        try {
            ApTemplatePolicy policy = new ApTemplatePolicy(new ObjectMapper());
            for (JsonNode entry : policy.defaults()) rules.set(entry.path("template_id").asText(), entry);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return ApTemplateContent.configure(catalog.world("isekai_grey"), template.materials(), rules);
    }
}
