package com.gameneko.aigame.content;

import java.util.List;
import java.util.Optional;

/** 提供按标识读取世界模板的目录边界。 */
public interface TemplateCatalog {
    /** 返回所有可复用模板。
     * @return 模板目录
     */
    List<WorldTemplate> availableTemplates();

    /** 按标识读取模板。
     * @param templateId 模板稳定标识
     * @return 模板内容，标识无效或模板不存在时抛出异常
     */
    default WorldTemplate template(String templateId) {
        return findTemplate(templateId).orElseThrow(() -> new WorldContentException("Unsupported template: " + templateId));
    }

    /** 按精确标识查询单个模板。
     * @param templateId 模板稳定标识
     * @return 匹配的模板，标识无效或模板不存在时为空
     */
    Optional<WorldTemplate> findTemplate(String templateId);
}
