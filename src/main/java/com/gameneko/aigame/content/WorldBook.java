package com.gameneko.aigame.content;

import com.gameneko.aigame.engine.InitializationSchema;

/** 兼容旧引擎投影的世界书包装，同时保留拆分后的叙事数据。 */
public record WorldBook(WorldDefinition definition, InitializationSchema initializationSchema,
                        WorldbookDefinition narrative) {
    /** 兼容旧调用方的两参数构造器。
     * @param definition 引擎使用的合并世界内容
     * @param initializationSchema 世界书初始化字段
     */
    public WorldBook(WorldDefinition definition, InitializationSchema initializationSchema) {
        this(definition, initializationSchema, new WorldbookDefinition(definition.worldId(), definition.contentVersion(),
                definition.nameHint(), "", "legacy", "balanced", definition.descriptionHint(),
                java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of()));
    }

    public WorldBook {
        if (definition == null || initializationSchema == null || narrative == null) throw new IllegalArgumentException("World book content and schema are required");
    }

    /** 返回世界书稳定标识。
     * @return 世界书标识
     */
    public String id() { return definition.worldId(); }

    /** 返回世界书内容版本。
     * @return 内容版本
     */
    public String version() { return definition.contentVersion(); }

    /** 返回引用模板标识。
     * @return 模板标识
     */
    public String templateId() { return narrative.templateId(); }

    /** 返回世界类型。
     * @return 世界类型稳定标识
     */
    public String type() { return narrative.type(); }

    /** 返回故事基调。
     * @return 基调标识
     */
    public String tone() { return narrative.tone(); }
}
