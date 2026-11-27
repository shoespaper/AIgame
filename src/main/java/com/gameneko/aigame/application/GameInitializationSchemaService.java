package com.gameneko.aigame.application;

import com.gameneko.aigame.content.ContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.CharacterProfile;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 提供引擎表单定义，并校验玩家提交的两组初始化资料。 */
@Service
public final class GameInitializationSchemaService {
    private static final InitializationSchema NO_ENGINE = new InitializationSchema(List.of());

    private final ContentCatalog worlds;
    private final GameEngineFactory engineFactory;

    /** 装配世界目录和规则引擎工厂。
     * @param worlds 读取世界书定义的目录
     * @param engineFactory 根据选择加载规则引擎的工厂
     */
    public GameInitializationSchemaService(ContentCatalog worlds, GameEngineFactory engineFactory) {
        this.worlds = worlds;
        this.engineFactory = engineFactory;
    }

    /** 加载当前选择的引擎配置定义，纯叙事时返回空列表。
     * @param selection 世界书及可选引擎的选择
     */
    public List<InitializationField> engineFields(GameSelection selection) {
        return schema(runtime(selection)).fields();
    }

    /** 校验表单输入并在通过时返回可用于开局的资料。
     * @param selection 会话绑定的世界及引擎选择
     * @param profile 通用角色资料的原始 JSON 字段值
     * @param config 引擎配置的原始 JSON 字段值，纯叙事时应为空
     */
    public Validation validate(GameSelection selection, Map<String, ?> profile, Map<String, ?> config) {
        GameEngineRuntime<?, ?> runtime = runtime(selection);
        Map<String, String> profileErrors = new LinkedHashMap<>();
        Map<String, String> engineErrors = new LinkedHashMap<>();
        Map<String, InitializationValue> profileValues = values(profile, profileErrors);
        Map<String, InitializationValue> engineValues = values(config, engineErrors);

        CharacterProfile.SCHEMA.validate(profileValues).errors().forEach(profileErrors::putIfAbsent);
        schema(runtime).validate(engineValues).errors().forEach(engineErrors::putIfAbsent);
        CharacterProfile character = profileErrors.isEmpty() ? character(profileValues) : null;
        if (runtime != null && character != null && engineErrors.isEmpty()) {
            engineErrors.putAll(runtime.validateInitialization(character, engineValues).errors());
        }
        Setup setup = profileErrors.isEmpty() && engineErrors.isEmpty() ? new Setup(character, engineValues) : null;
        return new Validation(setup, Map.copyOf(profileErrors), Map.copyOf(engineErrors));
    }

    /** 加载所选世界对应的规则引擎。
     * @param selection 会话绑定的世界及引擎选择
     */
    private GameEngineRuntime<?, ?> runtime(GameSelection selection) {
        WorldDefinition world = worlds.world(selection.worldId());
        return selection.hasEngine() ? engineFactory.create(selection.engineId(), world) : null;
    }

    /** 返回运行时声明的配置校验规则。
     * @param runtime 当前规则运行时，纯叙事时为空
     */
    static InitializationSchema schema(GameEngineRuntime<?, ?> runtime) {
        return runtime == null ? NO_ENGINE : runtime.initializationSchema();
    }

    /** 从校验通过的通用字段构建可保存的角色资料。
     * @param values 已按通用角色 schema 校验的字段
     */
    static CharacterProfile character(Map<String, InitializationValue> values) {
        InitializationValue.Text name = (InitializationValue.Text) values.get("characterName");
        InitializationValue.IntegerNumber age = (InitializationValue.IntegerNumber) values.get("age");
        InitializationValue.Text gender = (InitializationValue.Text) values.get("gender");
        InitializationValue.Text background = (InitializationValue.Text) values.get("background");
        return new CharacterProfile(name.value(), (int) age.value(), gender.value(), background == null ? "" : background.value());
    }

    /** 将 JSON 标量转换为引擎协议使用的类型化字段。
     * @param supplied 请求中的字段，可空
     * @param errors 用于记录无法转换的字段错误
     */
    private static Map<String, InitializationValue> values(Map<String, ?> supplied, Map<String, String> errors) {
        Map<String, InitializationValue> values = new LinkedHashMap<>();
        if (supplied == null) return values;
        supplied.forEach((id, raw) -> {
            InitializationValue value = switch (raw) {
                case String text -> new InitializationValue.Text(text.strip());
                case Integer integer -> new InitializationValue.IntegerNumber(integer.longValue());
                case Long integer -> new InitializationValue.IntegerNumber(integer);
                case Double number when Double.isFinite(number) -> new InitializationValue.DecimalNumber(number);
                case Boolean flag -> new InitializationValue.Flag(flag);
                case null, default -> null;
            };
            if (value == null) errors.put(id, "field_type_invalid");
            else values.put(id, value);
        });
        return Map.copyOf(values);
    }

    /** 保存一份完整且已通过校验的开局配置。
     * @param character 已确认的通用角色资料
     * @param engineConfig 引擎初始化配置，纯叙事时为空映射
     */
    public record Setup(CharacterProfile character, Map<String, InitializationValue> engineConfig) {}

    /** 表单校验结果及分组后的字段错误。
     * @param setup 通过时返回开局配置，失败时为空
     * @param profileErrors 通用角色字段标识到错误码的映射
     * @param engineErrors 引擎字段标识到错误码的映射
     */
    public record Validation(Setup setup, Map<String, String> profileErrors, Map<String, String> engineErrors) {}
}
