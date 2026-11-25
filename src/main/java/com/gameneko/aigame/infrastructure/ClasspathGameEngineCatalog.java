package com.gameneko.aigame.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.engine.EngineTemplatePolicy;
import com.gameneko.aigame.engine.GameEngineCatalog;
import com.gameneko.aigame.engine.GameEngineDescriptor;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.rulesets.ap.ApTemplatePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/** 提供内置引擎的选择信息和模板注册策略。 */
@Component
public final class ClasspathGameEngineCatalog implements GameEngineCatalog {
    /** 装配 AP 模块提供的模板注册策略。
     * @param mapper 读取内置引擎规则的 JSON 编解码器
     */
    @Bean
    static EngineTemplatePolicy apTemplatePolicy(ObjectMapper mapper) throws IOException {
        return new ApTemplatePolicy(mapper);
    }

    @Override
    public List<GameEngineDescriptor> available() { return List.of(descriptor()); }

    @Override
    public GameEngineDescriptor engine(String engineId) {
        if ("ap".equalsIgnoreCase(engineId)) return descriptor();
        throw new IllegalArgumentException("Unsupported game engine: " + engineId);
    }

    private static GameEngineDescriptor descriptor() {
        return new GameEngineDescriptor("ap", "baseline-1", "AP",
                new InitializationSchema(List.of(
                        new InitializationField("archetype", "职业", InitializationField.ValueType.TEXT,
                                true, List.of("swordsman", "mage", "ranger", "alchemist"), null, null, "请选择职业"),
                        new InitializationField("difficulty", "难度", InitializationField.ValueType.TEXT,
                                true, List.of(), null, null, "请选择难度"))));
    }
}
