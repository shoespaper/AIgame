package com.gameneko.aigame.application.port;

import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.ConfirmedEvent;
import com.gameneko.aigame.engine.WorldEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 游戏存档持久化端口，写入与删除应使用同一实例锁，以便应用层原子地校验并更新存档。 */
public interface GameRepository {
    Optional<GameInstance<?>> load(String gameId);

    void save(GameInstance<?> instance);

    /** 将游玩回合的结算回执和状态一起提交。
     * @param instance 结算后的权威实例
     * @param receipt 同次提交的回合回执
     */
    default void saveWithNarrationReceipt(GameInstance<?> instance, NarrationReceipt receipt) {
        throw new UnsupportedOperationException("Narration receipt persistence is not supported");
    }

    /** 查找可用于断线恢复的权威结算回执。
     * @param gameId 游戏存档标识
     * @param turnId 客户端生成的回合标识
     */
    default Optional<NarrationReceipt> narrationReceipt(String gameId, UUID turnId) {
        return Optional.empty();
    }

    /** 用于重新展示已提交的结算，不保存玩家输入或模型草稿。
     * @param turnId 该回合的唯一标识
     * @param revision 该回合已经保存的修订号
     * @param events 引擎确认事件
     * @param worldEvents 本轮新增的世界事件
     */
    record NarrationReceipt(UUID turnId, long revision, List<ConfirmedEvent> events, List<WorldEvent> worldEvents) {}

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
