package com.gameneko.aigame.content;

import java.util.List;
import java.util.Map;

/** 提供世界初始化所需的定义与完整的叙事资料。
 * @param definition 世界初始化用的元数据
 * @param narrative 世界书正文及结构化设定
 */
public record WorldBook(WorldDefinition definition, WorldbookDefinition narrative) {
    /** 从基础世界定义生成叙事包装。
     * @param definition 世界初始化用的元数据
     */
    public WorldBook(WorldDefinition definition) {
        this(definition, new WorldbookDefinition(definition.worldId(), definition.contentVersion(),
                definition.nameHint(), "", "legacy", "balanced", definition.descriptionHint(),
                List.of(), List.of(), List.of(), Map.of()));
    }

    /** 校验世界定义与叙事资料都已提供。 */
    public WorldBook {
        if (definition == null || narrative == null) throw new IllegalArgumentException("World book content is required");
    }

    /** 返回世界书稳定标识。 */
    public String id() { return definition.worldId(); }

    /** 返回世界书内容版本。 */
    public String version() { return definition.contentVersion(); }

    /** 返回引用模板标识。 */
    public String templateId() { return narrative.templateId(); }

    /** 返回世界类型。 */
    public String type() { return narrative.type(); }

    /** 返回故事基调。 */
    public String tone() { return narrative.tone(); }
}
