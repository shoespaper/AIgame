package com.gameneko.aigame.engine;

/** 描述规则选择目录中的一个引擎。
 * @param id 引擎标识
 * @param version 引擎版本
 * @param displayName 展示名称
 */
public record GameEngineDescriptor(String id, String version, String displayName) {
    /** 校验引擎的目录信息。 */
    public GameEngineDescriptor {
        if (id == null || id.isBlank() || version == null || version.isBlank()
                || displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Engine descriptor is incomplete");
    }
}
