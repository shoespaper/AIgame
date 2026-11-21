package com.gameneko.aigame.content;

import java.util.List;

/** Registry boundary for world books, kept separate from game engine registration. */
public interface WorldBookCatalog {
    /** 返回可供新游戏选择的世界书目录。 */
    List<WorldBook> available();

    /** 按稳定标识读取一个世界书。 */
    /** @param worldId 世界书标识 */
    WorldBook worldBook(String worldId);
}
