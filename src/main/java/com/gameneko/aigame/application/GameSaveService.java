package com.gameneko.aigame.application;

import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.application.port.GameRepository.SavedGame;
import com.gameneko.aigame.engine.GameInstance;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.List;

/** 保存、读取和删除权威游戏实例。 */
@Service
public final class GameSaveService {
    private final GameRepository repository;

    /** 创建游戏存档服务。
     * @param repository 游戏存档端口
     */
    public GameSaveService(GameRepository repository) {
        this.repository = repository;
    }

    /** 保存新建游戏。
     * @param instance 新建的权威游戏实例
     */
    public void save(GameInstance<?> instance) {
        repository.save(Objects.requireNonNull(instance, "instance"));
    }

    /** 按客户端修订号保存权威快照。
     * @param gameId 游戏存档标识
     * @param expectedRevision 客户端观察到的修订号
     */
    public void checkpoint(String gameId, long expectedRevision) {
        synchronized (repository) {
            GameInstance<?> instance = load(gameId);
            GameRequestException.checkRevision(instance, expectedRevision, null);
            repository.save(instance);
        }
    }

    /** 按游戏标识读取存档。
     * @param gameId 游戏存档标识
     */
    public GameInstance<?> load(String gameId) {
        return repository.load(gameId).orElseThrow(() -> new GameRequestException(GameRequestException.Code.SAVE_NOT_FOUND, "Save not found: " + gameId));
    }

    /** 永久删除指定游戏存档。
     * @param gameId 游戏存档标识
     */
    public void delete(String gameId) { repository.delete(gameId); }

    /** 返回存档实例及保存时间，供客户端展示可恢复的游戏元数据。 */
    public List<SavedGame> list() { return repository.list(); }
}
