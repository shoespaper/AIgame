package com.gameneko.aigame.content;

import java.util.List;

/** 提供按标识和世界类型读取世界模板的目录边界。 */
public interface TemplateCatalog {
    /** 返回所有可复用模板。
     * @return 模板目录
     */
    List<WorldTemplate> availableTemplates();

    /** 按标识读取模板。
     * @param templateId 模板稳定标识
     * @return 模板内容
     */
    WorldTemplate template(String templateId);

    /** 按世界类型返回可复用的模板，空类型返回空列表。
     * @param type 世界类型稳定标识
     * @return 与类型匹配的模板
     */
    default List<WorldTemplate> templatesForType(String type) {
        if (type == null || type.isBlank()) return List.of();
        return availableTemplates().stream().filter(template -> type.equals(template.type())).toList();
    }
}
