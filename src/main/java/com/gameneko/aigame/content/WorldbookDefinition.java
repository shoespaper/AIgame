package com.gameneko.aigame.content;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 世界书实例只保存模板之外的叙事设定。 */
public record WorldbookDefinition(String id, String contentVersion, String name, String type, String templateId,
                                  String tone, String descriptionHint, List<Map<String, String>> factions,
                                  List<String> originContexts, List<String> worldTropes,
                                  Map<String, Object> adventurerGuild) {
    private static final Pattern TYPE_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");

    /** 判断世界类型是否使用合法的稳定标识。
     * @param type 待校验的类型标识，可为空
     * @return 以小写字母开头、由小写字母数字下划线或连字符组成的 2 至 64 字符标识返回真
     */
    public static boolean isValidTypeId(String type) {
        return type != null && TYPE_ID.matcher(type).matches();
    }

    /** 创建并规范化世界书叙事集合。
     * @param id 世界书稳定标识
     * @param contentVersion 内容版本
     * @param name 世界展示名称
     * @param type 世界类型稳定标识
     * @param templateId 引用的模板标识
     * @param tone 故事基调，取 light、balanced 或 hardcore
     * @param descriptionHint 世界背景提示
     * @param factions 世界主要势力
     * @param originContexts 可用出身起点
     * @param worldTropes 世界规则和常识
     * @param adventurerGuild 公会叙事资料
     */
    public WorldbookDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Worldbook id is required");
        templateId = templateId == null ? "" : templateId;
        contentVersion = contentVersion == null || contentVersion.isBlank() ? "1.0" : contentVersion;
        name = name == null ? id : name;
        type = type == null ? "" : type;
        tone = tone == null || tone.isBlank() ? "balanced" : tone;
        descriptionHint = descriptionHint == null ? "" : descriptionHint;
        factions = factions == null ? List.of() : factions.stream().map(Map::copyOf).toList();
        originContexts = originContexts == null ? List.of() : List.copyOf(originContexts);
        worldTropes = worldTropes == null ? List.of() : List.copyOf(worldTropes);
        adventurerGuild = adventurerGuild == null ? Map.of() : Map.copyOf(adventurerGuild);
    }
}
