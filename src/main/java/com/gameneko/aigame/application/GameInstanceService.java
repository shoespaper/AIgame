package com.gameneko.aigame.application;

import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/** Creates a complete game envelope around the current initialization result. */
@Service
public final class GameInstanceService {
    private final WorldInitializationService worldInitializationService;
    private final ContentCatalog contentCatalog;
    private final GameEngineFactory gameEngineFactory;

    @Autowired
    public GameInstanceService(WorldInitializationService worldInitializationService,
                               ContentCatalog contentCatalog,
                               GameEngineFactory gameEngineFactory) {
        this.worldInitializationService = worldInitializationService;
        this.contentCatalog = contentCatalog;
        this.gameEngineFactory = gameEngineFactory;
    }

    /** Compatibility constructor for direct unit-test callers; Spring uses the registry-backed constructor. */
    public GameInstanceService(WorldInitializationService worldInitializationService, ContentCatalog contentCatalog) {
        this(worldInitializationService, contentCatalog, (engineId, world) -> {
            if (!"ap".equalsIgnoreCase(engineId)) throw new IllegalArgumentException("Unsupported game engine: " + engineId);
            return new ApGameEngineRuntime(world);
        });
    }

    public GameInstance<?> initialize(String gameId, InitializeWorldRequest request) {
        return initialize(gameId, request, null);
    }

    /**
     * Compatibility entry point for the pre-engine API. New callers should submit
     * answers through the map-based overload so this service does not manufacture
     * engine-owned fields or silently fill missing required answers.
     */
    @Deprecated
    public GameInstance<?> initialize(String gameId, InitializeWorldRequest request, String engineId) {
        Objects.requireNonNull(request, "request");
        return initialize(gameId, request, engineId, legacyAnswers(request));
    }

    /** Creates an instance from the selected world and already parsed initialization answers. */
    public GameInstance<?> initialize(String gameId,
                                      InitializeWorldRequest request,
                                      String engineId,
                                      Map<String, InitializationValue> answers) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(answers, "answers");
        var definition = contentCatalog.world(request.worldId());
        var worldSchema = definition.initializationSchema();
        var runtime = engineId == null || engineId.isBlank() ? null : gameEngineFactory.create(engineId, definition);
        var fields = new ArrayList<InitializationField>(worldSchema.fields());
        if (runtime != null) fields.addAll(runtime.initializationSchema().fields());
        var validation = new InitializationSchema(fields).validate(answers);
        if (!validation.accepted()) throw new IllegalArgumentException("Invalid initialization answers: " + validation.errors());
        var worldState = worldInitializationService.initialize(request);
        if (runtime == null) return GameInstance.narrativeOnly(gameId, worldState);
        var state = runtime.initialize(Map.copyOf(answers));
        return GameInstance.initialized(gameId, worldState, runtime.id(), runtime.version(), state);
    }

    /**
     * Legacy initialization answers for the pre-engine API. Should be deleted after the API is removed.
     */
    private static Map<String, InitializationValue> legacyAnswers(InitializeWorldRequest request) {
        int age = request.age() == null ? 16 : request.age();
        var answers = new HashMap<String, InitializationValue>();
        answers.put("characterName", new InitializationValue.Text(request.characterName() == null ? "旅人" : request.characterName()));
        answers.put("age", new InitializationValue.IntegerNumber(age));
        answers.put("gender", new InitializationValue.Text(request.gender() == null ? "未定" : request.gender()));
        answers.put("archetype", new InitializationValue.Text(request.archetype() == null ? "swordsman" : request.archetype()));
        answers.put("difficulty", new InitializationValue.Text(request.difficulty() == null ? "adventure" : request.difficulty()));
        return answers;
    }
}
