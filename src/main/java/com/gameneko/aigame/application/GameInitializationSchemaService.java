package com.gameneko.aigame.application;

import com.gameneko.aigame.content.WorldBookCatalog;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

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

    /**
     * 组合世界书字段与选中引擎的字段为一个 schema。
     * @param selection 世界书与可选引擎
     */
    public InitializationSchema schema(GameSelection selection) {
        var world = worldBooks.worldBook(selection.worldId());
        var fields = new ArrayList<>(world.initializationSchema().fields());
        if (selection.hasEngine()) {
            var runtime = engineFactory.create(selection.engineId(), world.definition());
            for (InitializationField field : runtime.initializationSchema().fields()) {
                if (fields.stream().anyMatch(existing -> existing.id().equals(field.id()))) {
                    throw new IllegalArgumentException("Initialization field collision: " + field.id());
                }
                fields.add(field);
            }
        }
        return new InitializationSchema(fields);
    }
}
