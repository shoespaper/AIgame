package com.gameneko.aigame.application;

import com.gameneko.aigame.application.GameInitializationSchemaService.Setup;
import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.CharacterProfile;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.GameState;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 根据通用角色资料和可选引擎配置创建权威游戏实例。 */
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

    /** 装配世界初始化、静态内容和引擎服务。
     * @param worldInitializationService 创建世界初始状态的服务
     * @param contentCatalog 读取世界书定义的目录
     * @param gameEngineFactory 加载指定引擎的工厂
     */
    @Autowired
    public GameInstanceService(WorldInitializationService worldInitializationService,
                               ContentCatalog contentCatalog, GameEngineFactory gameEngineFactory) {
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

    /** 使用默认角色和引擎配置直接创建实例。
     * @param gameId 游戏标识
     * @param request 世界初始化请求
     * @param engineId 可空的规则引擎标识
     */
    public GameInstance<?> initializeWithDefaults(String gameId, InitializeWorldRequest request, String engineId) {
        return initializeWithDefaults(gameId, request, engineId, Map.of());
    }

    /** 应用直接初始化调用方提供的字段覆盖。
     * @param gameId 游戏标识
     * @param request 世界初始化请求
     * @param engineId 可空的规则引擎标识
     * @param overrides 类型化角色及引擎字段覆盖值
     */
    public GameInstance<?> initializeWithDefaults(String gameId, InitializeWorldRequest request, String engineId,
                                                  Map<String, InitializationValue> overrides) {
        GameEngineRuntime<?, ?> runtime = runtime(engineId, contentCatalog.world(request.worldId()));
        CharacterProfile defaults = CharacterProfile.defaults();
        Map<String, InitializationValue> values = new HashMap<>();
        if (runtime != null) values.putAll(runtime.defaultInitializationAnswers());
        values.put("characterName", new InitializationValue.Text(defaults.name()));
        values.put("age", new InitializationValue.IntegerNumber(defaults.age()));
        values.put("gender", new InitializationValue.Text(defaults.gender()));
        values.putAll(overrides);
        return initialize(gameId, request, engineId, values);
    }

    /** 使用默认角色创建纯叙事实例。
     * @param gameId 游戏标识
     * @param request 世界初始化请求
     */
    public GameInstance<?> initialize(String gameId, InitializeWorldRequest request) {
        return initializeWithDefaults(gameId, request, null);
    }

    /** 处理直接初始化入口传入的类型化字段。
     * @param gameId 游戏标识
     * @param request 世界初始化请求
     * @param engineId 可空的规则引擎标识
     * @param answers 通用角色字段及引擎配置
     */
    public GameInstance<?> initialize(String gameId, InitializeWorldRequest request, String engineId,
                                      Map<String, InitializationValue> answers) {
        Objects.requireNonNull(answers, "answers");
        Map<String, InitializationValue> engineConfig = new HashMap<>(answers);
        Map<String, InitializationValue> profile = new HashMap<>();
        for (InitializationField field : CharacterProfile.SCHEMA.fields()) {
            if (engineConfig.containsKey(field.id())) profile.put(field.id(), engineConfig.remove(field.id()));
        }
        if (profile.containsValue(null) || engineConfig.containsValue(null)) {
            throw GameRequestException.invalid("初始化字段值不能为空。");
        }
        requireValid(CharacterProfile.SCHEMA.validate(profile));
        Setup setup = new Setup(GameInitializationSchemaService.character(profile), Map.copyOf(engineConfig));
        return initializeForOpening(gameId, request, engineId, setup, ignored -> {});
    }

    /** 在模板登记完成后按确认资料创建游戏。
     * @param gameId 本局游戏标识
     * @param request 世界初始化请求
     * @param engineId 可空的规则引擎标识
     * @param setup 本局通用角色资料与引擎配置
     * @param progress 接收模板生成进度
     */
    GameInstance<?> initializeForOpening(String gameId, InitializeWorldRequest request, String engineId, Setup setup,
                                         Consumer<CreationProgress> progress) {
        WorldDefinition definition = contentCatalog.world(request.worldId());
        GameEngineRuntime<?, ?> runtime = runtime(engineId, definition);
        requireValid(GameInitializationSchemaService.schema(runtime).validate(setup.engineConfig()));
        if (runtime != null) requireValid(runtime.validateInitialization(setup.character(), setup.engineConfig()));

        if (confirmedTemplates != null) {
            progress.accept(new CreationProgress("template_lookup", null, null, false, "正在查找可复用模板"));
            confirmedTemplates.confirm(gameId, contentCatalog.worldBook(request.worldId()).narrative(), engineId, progress);
            if (runtime != null) runtime = gameEngineFactory.createForGame(engineId, definition, gameId);
        }
        WorldState worldState = worldInitializationService.initialize(request);
        if (runtime == null) {
            return GameInstance.narrativeOnly(gameId, worldState, setup.character());
        }
        GameState state = runtime.initialize(setup.character(), setup.engineConfig());
        return GameInstance.initialized(gameId, worldState, setup.character(), runtime.id(), runtime.version(), state);
    }

    /** 按选择创建用于校验的规则引擎。
     * @param engineId 可空的引擎标识
     * @param world 当前世界定义
     */
    private GameEngineRuntime<?, ?> runtime(String engineId, WorldDefinition world) {
        return engineId == null || engineId.isBlank() ? null : gameEngineFactory.create(engineId, world);
    }

    /** 以稳定错误码拒绝无效初始化资料。
     * @param validation 角色或引擎校验结果
     */
    private static void requireValid(InitializationSchema.ValidationResult validation) {
        if (!validation.accepted()) {
            throw new GameRequestException(GameRequestException.Code.INVALID_INITIALIZATION_ANSWERS,
                    "开局资料未通过校验：" + validation.errors());
        }
    }

}
