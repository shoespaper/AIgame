package com.gameneko.aigame.application.port;

import com.gameneko.aigame.content.WorldbookDefinition;

import java.util.List;

/** 管理玩家创建的可编辑世界书。 */
public interface WorldbookRepository {
    /** 列出可编辑世界书的稳定标识。 */
    List<String> available();

    /** 创建自定义世界书，标识已存在时拒绝写入。
     * @param worldbook 要保存的世界书定义
     */
    void save(WorldbookDefinition worldbook);

    /** 保存自定义世界书。
     * @param worldbook 要保存的世界书定义
     * @param overwrite 是否允许覆盖已有世界书
     */
    void save(WorldbookDefinition worldbook, boolean overwrite);

    /** 删除指定自定义世界书。
     * @param id 要删除的世界书稳定标识
     */
    void delete(String id);
}
