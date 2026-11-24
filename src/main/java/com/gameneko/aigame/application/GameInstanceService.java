package com.gameneko.aigame.application;

import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;

/** 根据世界书共享字段与可选引擎字段创建完整游戏实例。 */
@Service
public final class GameInstanceService {
    private final WorldInitializationService worldInitializationService;
    private final ContentCatalog contentCatalog;
    private final GameEngineFactory gameEngineFactory;

    private ConfirmedTemplateRegistry confirmedTemplates;

    /** 接入开局确认时使用的注册目录。
     * @param registry 持久化世界与引擎模板的目录
     */
    @Autowired
    void configureTemplates(ConfirmedTemplateRegistry registry) { this.confirmedTemplates = registry; }

    @Autowired
    public GameInstanceService(WorldInitializationService worldInitializationService,
                               ContentCatalog contentCatalog,
                               GameEngineFactory gameEngineFactory) {
        this.worldInitializationService = worldInitializationService;
        this.contentCatalog = contentCatalog;
        this.gameEngineFactory = gameEngineFactory;
    }

    /** 创建使用默认不支持引擎工厂的纯叙事服务。
     * @param worldInitializationService 创建世界初始状态的服务
     * @param contentCatalog 读取世界书定义的目录
     */
    public GameInstanceService(WorldInitializationService worldInitializationService, ContentCatalog contentCatalog) {
        this(worldInitializationService, contentCatalog, GameEngineFactory.unsupported());
    }

    /** 使用世界书与引擎各自提供的默认答案创建兼容入口游戏。
     * @param gameId 游戏标识
     * @param request 世界书共享字段覆盖请求
     * @param engineId 可空的规则引擎标识
     * @return 已初始化的权威游戏实例
     */
    public GameInstance<?> initializeWithDefaults(String gameId, InitializeWorldRequest request, String engineId) {
        return initializeWithDefaults(gameId, request, engineId, Map.of());
    }

    /** 合并世界书和引擎模块各自的默认值及调用方覆盖值后创建游戏。
     * @param gameId 游戏标识
     * @param request 世界书共享字段覆盖请求
     * @param engineId 可空的规则引擎标识
     * @param overrides 调用方声明的类型化覆盖字段
     * @return 已初始化的权威游戏实例
     */
    public GameInstance<?> initializeWithDefaults(String gameId, InitializeWorldRequest request, String engineId,
                                                   Map<String, InitializationValue> overrides) {
        Objects.requireNonNull(request, "request");
        var definition = contentCatalog.world(request.worldId());
        var answers = new HashMap<>(definition.defaultInitializationAnswers());
        if (engineId != null && !engineId.isBlank()) {
            var runtime = gameEngineFactory.create(engineId, definition);
            answers.putAll(runtime.defaultInitializationAnswers());
        }
        answers.putAll(overrides);
        return initialize(gameId, request, engineId, Map.copyOf(answers));
    }

    public GameInstance<?> initialize(String gameId, InitializeWorldRequest request) {
        return initializeWithDefaults(gameId, request, null);
    }

    /** 使用已由初始化流程校验的类型化答案创建游戏实例。
     * @param gameId 游戏标识
     * @param request 世界书共享字段覆盖请求
     * @param engineId 可空的规则引擎标识
     * @param answers 已确认的世界书与引擎字段值
     * @return 新建的权威游戏实例
     */
    public GameInstance<?> initialize(String gameId,
                                      InitializeWorldRequest request,
                                      String engineId,
                                      Map<String, InitializationValue> answers) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(answers, "answers");
        WorldDefinition definition = contentCatalog.world(request.worldId());
        GameEngineRuntime<?, ?> runtime = engineId == null || engineId.isBlank() ? null : gameEngineFactory.create(engineId, definition);
        InitializationSchema validationSchema = definition.initializationSchema();
        if (runtime != null) {
            validationSchema = GameInitializationSchemaService.compose(validationSchema, runtime.initializationSchema());
        }
        InitializationSchema.ValidationResult validation = validationSchema.validate(answers);
        if (!validation.accepted()) throw new IllegalArgumentException("Invalid initialization answers: " + validation.errors());
        if (confirmedTemplates != null) {
            confirmedTemplates.confirm(gameId, contentCatalog.worldBook(request.worldId()).narrative(), engineId);
            if (runtime != null) runtime = gameEngineFactory.createForGame(engineId, definition, gameId);
        }
        WorldState worldState = worldInitializationService.initialize(request);
        if (runtime == null) return GameInstance.narrativeOnly(gameId, worldState);
        GameState state = runtime.initialize(Map.copyOf(answers));
        return GameInstance.initialized(gameId, worldState, runtime.id(), runtime.version(), state);
    }

}
