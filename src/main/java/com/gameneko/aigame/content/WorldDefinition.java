package com.gameneko.aigame.content;

/** 保存开局时使用的世界书元数据。
 * @param worldId 世界书标识
 * @param contentVersion 世界内容版本
 * @param nameHint 世界展示名称
 * @param descriptionHint 世界叙事说明
 */
public record WorldDefinition(String worldId, String contentVersion, String nameHint, String descriptionHint) {}
