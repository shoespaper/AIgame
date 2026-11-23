package com.gameneko.aigame.application;

/** 世界书共享的世界初始字段，不包含任何可选引擎专属字段。
 * @param worldId 世界书稳定标识
 * @param name 可空的世界名称覆盖值
 * @param description 可空的世界描述覆盖值
 * @param location 可空的开场地点自由文本
 * @param region 可空的世界模板区域标识
 * @param time 可空的开场时间
 * @param mainQuest 可空的开场主线方向
 * @param originScene 可空的开场场景描述
 */
public record InitializeWorldRequest(String worldId, String name, String description,
                                     String location, String region, String time,
                                     String mainQuest, String originScene) {
    /** 创建只有世界书标识的默认开局请求。
     * @param worldId 世界书稳定标识
     */
    public static InitializeWorldRequest defaults(String worldId) {
        return new InitializeWorldRequest(worldId, null, null, null, null, null, null, null);
    }
}
