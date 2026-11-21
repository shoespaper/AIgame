package com.gameneko.aigame.application.port;

import com.gameneko.aigame.engine.GameInstance;

import java.util.Optional;
import java.util.List;

/** Application-owned persistence port; adapters implement it outside the core. */
public interface GameRepository {
    Optional<GameInstance<?>> load(String gameId);

    void save(GameInstance<?> instance);

    /** 返回当前持久化适配器可见的全部存档实例。 */
    default List<GameInstance<?>> list() { return List.of(); }
}
