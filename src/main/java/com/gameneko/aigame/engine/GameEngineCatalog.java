package com.gameneko.aigame.engine;

import java.util.List;

/** Registry boundary for optional game engines. */
public interface GameEngineCatalog {
    List<GameEngineDescriptor> available();

    GameEngineDescriptor engine(String engineId);
}
