package com.gameneko.aigame.application;

import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameEngineRuntime;

import java.util.Objects;

/** Creates the selected engine for a specific world without exposing engine types to orchestration. */
@FunctionalInterface
public interface GameEngineFactory {
    GameEngineRuntime<?, ?> create(String engineId, WorldDefinition world);

    /** 按开局时固定的模板创建当前存档的规则运行时。
     * @param engineId 引擎标识
     * @param world 世界书基础内容
     * @param gameId 当前游戏标识
     */
    default GameEngineRuntime<?, ?> createForGame(String engineId, WorldDefinition world, String gameId) {
        return create(engineId, world);
    }

    static GameEngineFactory unsupported() {
        return (engineId, world) -> { throw new GameRequestException(GameRequestException.Code.UNSUPPORTED_ENGINE, "Unsupported game engine: " + engineId); };
    }
}
