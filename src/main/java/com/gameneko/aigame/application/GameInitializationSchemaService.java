package com.gameneko.aigame.application;

import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.content.WorldBook;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 组合世界书与引擎的初始化字段；引擎字段通过绑定世界的运行时获取，保证与 finalize 阶段一致。 */
@Service
public final class GameInitializationSchemaService {
    private final WorldBookCatalog worldBooks;
    private final GameEngineFactory engineFactory;

    /**
     * 创建 schema 服务。
     * @param worldBooks 世界书目录
     * @param engineFactory 按世界创建引擎运行时的工厂
     */
    public GameInitializationSchemaService(WorldBookCatalog worldBooks, GameEngineFactory engineFactory) {
        this.worldBooks = worldBooks;
        this.engineFactory = engineFactory;
    }

    /** 组合世界书共享字段、引擎专属字段和引擎提供的初始化访谈模板。
     * @param selection 世界书与可选引擎选择
     * @return 完整字段 schema 和本次访谈提示模板
     */
    public InitializationProfile profile(GameSelection selection) {
        WorldBook world = worldBooks.worldBook(selection.worldId());
        InitializationSchema worldSchema = world.initializationSchema();
        List<InitializationField> engineFields = List.of();
        String enginePrompt = "";
        if (selection.hasEngine()) {
            GameEngineRuntime<?, ?> runtime = engineFactory.create(selection.engineId(), world.definition());
            engineFields = runtime.initializationSchema().fields();
            enginePrompt = runtime.initializationPrompt();
            if (enginePrompt == null || enginePrompt.isBlank()) {
                throw new IllegalArgumentException("Selected engine initialization prompt is required");
            }
        }
        Set<String> engineFieldIds = engineFields.stream().map(InitializationField::id).collect(Collectors.toSet());
        List<InitializationField> worldFields = worldSchema.fields().stream()
                .filter(field -> !engineFieldIds.contains(field.id())).toList();
        InitializationSchema effectiveWorldSchema = new InitializationSchema(worldFields);
        return new InitializationProfile(compose(effectiveWorldSchema, new InitializationSchema(engineFields)),
                effectiveWorldSchema, enginePrompt);
    }

    /** 组合世界书与引擎字段；同名字段保留引擎定义并覆盖世界书定义。
     * @param worldSchema 世界书字段 schema，已排除引擎同名字段
     * @param engineSchema 引擎字段 schema
     * @return 引擎优先的组合 schema
     */
    static InitializationSchema compose(InitializationSchema worldSchema, InitializationSchema engineSchema) {
        Map<String, InitializationField> fields = new LinkedHashMap<>();
        worldSchema.fields().forEach(field -> fields.put(field.id(), field));
        engineSchema.fields().forEach(field -> fields.put(field.id(), field));
        return new InitializationSchema(new ArrayList<>(fields.values()));
    }

    /** 仅读取组合字段 schema。
     * @param selection 世界书与可选引擎选择
     * @return 世界书与引擎共享的初始化字段 schema
     */
    public InitializationSchema schema(GameSelection selection) {
        return profile(selection).schema();
    }

    /** 保存初始化所需的 schema 归属和引擎提示模板。
     * @param schema 世界书与引擎组合后的验证 schema
     * @param worldSchema 世界书拥有的共享字段 schema
     * @param enginePrompt 可选引擎的访谈提示，无引擎时为空字符串
     */
    public record InitializationProfile(InitializationSchema schema, InitializationSchema worldSchema,
                                        String enginePrompt) {}

}
