package com.gameneko.aigame.application;

import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.engine.GameEngineCatalog;
import com.gameneko.aigame.engine.GameEngineDescriptor;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/** Composes world-book and optional-engine questions without knowing AP-specific state. */
@Service
public final class GameInitializationSchemaService {
    private final WorldBookCatalog worldBooks;
    private final GameEngineCatalog engines;

    public GameInitializationSchemaService(WorldBookCatalog worldBooks, GameEngineCatalog engines) {
        this.worldBooks = worldBooks;
        this.engines = engines;
    }

    public InitializationSchema schema(GameSelection selection) {
        var fields = new ArrayList<>(worldBooks.worldBook(selection.worldId()).initializationSchema().fields());
        if (selection.hasEngine()) {
            GameEngineDescriptor engine = engines.engine(selection.engineId());
            for (InitializationField field : engine.initializationSchema().fields()) {
                if (fields.stream().anyMatch(existing -> existing.id().equals(field.id()))) {
                    throw new IllegalArgumentException("Initialization field collision: " + field.id());
                }
                fields.add(field);
            }
        }
        return new InitializationSchema(fields);
    }
}
