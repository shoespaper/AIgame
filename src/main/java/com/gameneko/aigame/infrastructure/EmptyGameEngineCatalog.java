package com.gameneko.aigame.infrastructure;

import com.gameneko.aigame.engine.GameEngineCatalog;
import com.gameneko.aigame.engine.GameEngineDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Empty default registry until concrete engines are registered by the runtime module. */
@Component
public final class EmptyGameEngineCatalog implements GameEngineCatalog {
    @Override
    public List<GameEngineDescriptor> available() { return List.of(); }

    @Override
    public GameEngineDescriptor engine(String engineId) {
        throw new IllegalArgumentException("Unsupported game engine: " + engineId);
    }
}
