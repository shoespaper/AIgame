package com.gameneko.aigame.application.port;

import com.gameneko.aigame.engine.GameInstance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 游戏存档持久化端口，写入与删除应使用同一实例锁，以便应用层原子地校验并更新存档。 */
public interface GameRepository {
    Optional<GameInstance<?>> load(String gameId);

    void save(GameInstance<?> instance);

    /** 删除指定存档，已不存在时视为成功。
     * @param gameId 游戏存档标识
     */
    default void delete(String gameId) {
        throw new UnsupportedOperationException("Save deletion is not supported by this repository");
    }

    /** 返回当前持久化适配器可见的全部存档及保存时间。 */
    default List<SavedGame> list() { return List.of(); }

    /** 封装同一次读取获得的存档及其保存时间。
     * @param instance 已保存的权威游戏实例
     * @param updatedAt 存档的最后写入时间，可为空
     */
    record SavedGame(GameInstance<?> instance, Instant updatedAt) {}
}
