package com.gameneko.aigame.application.port;

import com.gameneko.aigame.content.WorldTemplate;

/** 保存玩家创建的世界模板。 */
public interface TemplateRepository {
    /** 创建模板，标识已存在时拒绝写入。
     * @param template 要保存的世界模板
     */
    void save(WorldTemplate template);
}
