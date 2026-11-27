package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.GameInitializationSchemaService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.CharacterProfile;
import com.gameneko.aigame.engine.GameEngineRuntime;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证通用角色资料和可选引擎的表单定义及校验。 */
class GameInitializationSchemaTests {
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());

    /** 相同 ID 在通用资料和引擎配置中使用各自的定义。 */
    @Test
    void profileAndEngineFieldsHaveSeparateOwnership() {
        InitializationField field = new InitializationField("characterName", "阵营编号", InitializationField.ValueType.INTEGER,
                true, List.of(), 1L, 20L, "");
        GameEngineRuntime<?, ?> engine = mock(GameEngineRuntime.class);
        when(engine.initializationSchema()).thenReturn(new InitializationSchema(List.of(field)));
        when(engine.validateInitialization(new CharacterProfile("林舟", 25, "未指定", ""), Map.of(
                "characterName", new InitializationValue.IntegerNumber(3))))
                .thenReturn(new InitializationSchema.ValidationResult(false, Map.of("characterName", "restricted")));
        GameInitializationSchemaService service = new GameInitializationSchemaService(catalog, (id, world) -> engine);
        GameSelection selection = new GameSelection("isekai_grey", "test");
        assertEquals(List.of(field), service.engineFields(selection));
        GameInitializationSchemaService.Validation result = service.validate(selection,
                Map.of("characterName", "林舟", "age", 25, "gender", "未指定"), Map.of("characterName", 3));
        assertTrue(result.profileErrors().isEmpty());
        assertEquals(Map.of("characterName", "restricted"), result.engineErrors());
    }

    /** 纯叙事仍要求基础身份，并拒绝任何引擎专用配置。 */
    @Test
    void narrativeOnlyRejectsUnsupportedConfigurationAndInvalidProfile() {
        GameInitializationSchemaService service = new GameInitializationSchemaService(catalog, new ClasspathGameEngineFactory());
        GameSelection selection = new GameSelection("isekai_grey", null);
        assertTrue(service.engineFields(selection).isEmpty());
        GameInitializationSchemaService.Validation result = service.validate(selection,
                Map.of("characterName", " ", "age", 17.8), Map.of("difficulty", "adventure"));
        assertNull(result.setup());
        assertEquals(Map.of("characterName", "field_required", "age", "field_type_invalid", "gender", "field_required"),
                result.profileErrors());
        assertEquals(Map.of("difficulty", "field_not_supported"), result.engineErrors());
    }

    /** AP 提供参与实际配置校验的职业和难度选项及显示名称。 */
    @Test
    void apFieldsHaveStableOptionsAndLabels() {
        GameInitializationSchemaService service = new GameInitializationSchemaService(catalog, new ClasspathGameEngineFactory());
        GameSelection selection = new GameSelection("isekai_grey", "ap");
        List<InitializationField> fields = service.engineFields(selection);
        assertEquals(List.of("archetype", "difficulty"), fields.stream().map(InitializationField::id).toList());
        assertEquals("冒险", fields.getLast().optionLabels().get("adventure"));
        GameInitializationSchemaService.Validation result = service.validate(selection,
                Map.of("characterName", " 旅人 ", "age", 22, "gender", "未指定", "background", " "),
                Map.of("archetype", "ranger", "difficulty", "adventure"));
        assertEquals(new CharacterProfile("旅人", 22, "未指定", ""), result.setup().character());
        assertEquals("field_option_invalid", service.validate(selection,
                Map.of("characterName", "旅人", "age", 22, "gender", "未指定"),
                Map.of("archetype", "unknown", "difficulty", "adventure")).engineErrors().get("archetype"));
    }
}
