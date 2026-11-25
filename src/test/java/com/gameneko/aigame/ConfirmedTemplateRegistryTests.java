package com.gameneko.aigame;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.port.NarrativeProvider;
import com.gameneko.aigame.content.TemplateCatalog;
import com.gameneko.aigame.content.WorldTemplate;
import com.gameneko.aigame.content.WorldbookDefinition;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeProviderException;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.persistence.ConfirmedTemplateRegistry;
import com.gameneko.aigame.rulesets.ap.ApTemplatePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 验证模板开局的精确匹配、按引擎增补以及无效提案的原子性。 */
class ConfirmedTemplateRegistryTests {
    private static final String WORLD = """
            {"world_template":{"template_id":"dusk","world_type":"dusk","name":"暮野",
              "regions":[],"npc_types":[],"monster_types":[],"spell_types":[],"talent_types":[],
              "item_types":[],"quest_templates":[],"event_templates":[],
              "skill_types":[{"id":"guard","name":"驻守","description":"守住关口"}]}}
            """;
    private static final String SUPPLEMENT = """
            {"engine_references":{"skill_types":{"guard":{"template_id":"dusk_guard"}}},
             "engine_template_registrations":{"ap":[
               {"template_id":"dusk_guard","entity_type":"skill","rule_type":"defend",
                "parameters":{"ap_cost":1,"guard":3}},
               {"template_id":"skill_guard","entity_type":"skill","rule_type":"defend",
                "parameters":{"ap_cost":9,"guard":9}}]}}
            """;

    @TempDir
    Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateCatalog catalog = mock(TemplateCatalog.class);
    private final NarrativeProvider provider = mock(NarrativeProvider.class);

    /** 验证缺失模板对象和错误身份会反馈给模型，且第三次有效提案可以完成登记。 */
    @Test
    void retriesWorldGenerationWithPreviousValidationError() throws IOException {
        when(catalog.findTemplate("dusk")).thenReturn(Optional.empty());
        String wrongIdentity = WORLD.replace("\"template_id\":\"dusk\"", "\"template_id\":\"fantasy_mist_frontier\"")
                .replace("\"world_type\":\"dusk\"", "\"world_type\":\"fantasy\"");
        when(provider.respond(any())).thenReturn(response("{}"), response(wrongIdentity), response(WORLD));
        ConfirmedTemplateRegistry registry = registry();

        registry.confirm("story", book(""), null);

        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(provider, times(3)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).systemInstruction().contains("world_template 缺失"));
        String thirdInstruction = prompts.getAllValues().get(2).systemInstruction();
        assertTrue(thirdInstruction.contains("期望 template_id=dusk、world_type=dusk"));
        assertTrue(thirdInstruction.contains("实际 template_id=\"fantasy_mist_frontier\"、world_type=\"fantasy\""));
        assertEquals("dusk", registry.world("story").path("template_id").asText());
    }

    /** 验证模板身份持续错误时最多尝试三次，且磁盘和内存均没有新增绑定。 */
    @Test
    void rejectsWorldAfterThreeInvalidProposalsWithoutBinding() throws IOException {
        when(catalog.findTemplate("dusk")).thenReturn(Optional.empty());
        when(provider.respond(any())).thenReturn(response("{}"));
        ConfirmedTemplateRegistry registry = registry();

        NarrativeProviderException failure = assertThrows(NarrativeProviderException.class,
                () -> registry.confirm("story", book(""), null));
        assertEquals("invalid_model_output", failure.reasonCode());

        verify(provider, times(3)).respond(any());
        assertTrue(registry.world("story").isMissingNode());
        assertTrue(Files.notExists(temporary.resolve("registry.json")));
    }

    /** 验证内层 JSON 和素材引用出错时都会反馈给模型，再用修正的整份提案开局。 */
    @Test
    void retriesMalformedProposalAndUnknownMonsterReference() throws IOException {
        when(catalog.findTemplate("dusk")).thenReturn(Optional.empty());
        String unknownMonster = WORLD.replace("\"regions\":[]",
                "\"regions\":[{\"id\":\"forest\",\"name\":\"林地\",\"monster_ids\":[\"missing_monster\"]}]");
        when(provider.respond(any())).thenReturn(response("{"), response(unknownMonster), response(WORLD));
        ConfirmedTemplateRegistry registry = registry();

        registry.confirm("story", book(""), null);

        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(provider, times(3)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).systemInstruction().contains("updates.template 的 JSON 无效"));
        assertTrue(prompts.getAllValues().get(2).systemInstruction()
                .contains("world_template.regions[0].monster_ids[0] 引用了未定义的怪物：\"missing_monster\""));
        assertTrue(registry.world("story").path("regions").isEmpty());
    }

    /** 验证首次以 AP 开局时，无效引用和无关新规则都不会流入下一轮。 */
    @Test
    void retriesFullApProposalWithoutRetainingFailedRule() throws IOException {
        when(catalog.findTemplate("dusk")).thenReturn(Optional.empty());
        String worldWithReference = WORLD.strip().replace("\"description\":\"守住关口\"",
                "\"description\":\"守住关口\",\"engines\":{\"ap\":{\"template_id\":\"dusk_guard\"}}");
        String proposal = worldWithReference.substring(0, worldWithReference.length() - 1) + """
                ,"engine_template_registrations":{"ap":[
                  {"template_id":"dusk_guard","entity_type":"skill","rule_type":"defend",
                   "parameters":{"ap_cost":1,"guard":3}}]}}
                """;
        String wrongReference = proposal.replace("\"template_id\":\"dusk_guard\"}}",
                "\"template_id\":\"missing_guard\"}}").replace("\"guard\":3", "\"guard\":9");
        String unusedRule = proposal.replace("\"template_id\":\"dusk_guard\"}}", "\"template_id\":\"skill_guard\"}}")
                .replace("\"guard\":3", "\"guard\":8");
        when(provider.respond(any())).thenReturn(response(wrongReference), response(unusedRule), response(proposal));
        ConfirmedTemplateRegistry registry = registry();

        registry.confirm("ap-game", book(""), "ap");

        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(provider, times(3)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).systemInstruction().contains("引用了未定义的引擎规则 missing_guard"));
        assertTrue(prompts.getAllValues().get(2).systemInstruction().contains("本轮新增的引擎规则未被世界素材引用：dusk_guard"));
        assertEquals(3, registry.rules("ap").at("/dusk_guard/parameters/guard").asInt());
        assertEquals("dusk_guard", registry.world("ap-game").at("/skill_types/0/engines/ap/template_id").asText());
    }

    /** 验证自定义世界先登记叙事素材，再补齐 AP 引用，重启后直接按已登记 ID 复用。 */
    @Test
    void registeredWorldReusesMaterialAndOnlyAddsSelectedEngine() throws IOException {
        when(catalog.findTemplate("dusk")).thenReturn(Optional.empty());
        String unusedRule = SUPPLEMENT.replace("\"template_id\":\"skill_guard\",\"entity_type\"",
                "\"template_id\":\"unused_guard\",\"entity_type\"");
        when(provider.respond(any())).thenReturn(response(WORLD), response(unusedRule), response(SUPPLEMENT));
        ConfirmedTemplateRegistry registry = registry();
        WorldbookDefinition book = book("");

        registry.confirm("story", book, null);
        JsonNode original = registry.world("story");
        assertTrue(registry.rules("ap").isMissingNode());
        registry.confirm("ap-game", book, "ap");
        assertEquals(original, registry.world("story"));
        assertEquals("dusk_guard", registry.world("ap-game").at("/skill_types/0/engines/ap/template_id").asText());
        assertEquals(3, registry.rules("ap").at("/dusk_guard/parameters/guard").asInt());
        assertEquals(4, registry.rules("ap").at("/skill_guard/parameters/guard").asInt());
        assertTrue(registry.rules("ap").path("unused_guard").isMissingNode());
        verify(catalog, times(2)).findTemplate("dusk");
        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(provider, times(3)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().get(2).systemInstruction().contains("本轮新增的引擎规则未被世界素材引用：unused_guard"));
        verifyNoMoreInteractions(catalog, provider);

        clearInvocations(catalog, provider);
        ConfirmedTemplateRegistry reloaded = registry();
        reloaded.confirm("story-again", book, null);
        reloaded.confirm("ap-again", book, "ap");
        assertEquals(original, reloaded.world("story-again"));
        assertEquals(registry.world("ap-game"), reloaded.world("ap-again"));
        verifyNoInteractions(catalog, provider);
    }

    /** 验证精确目录引用保留原 ID，仅生成缺失的定义，并拒绝规则类别错误的提案。 */
    @Test
    void unresolvedCatalogReferenceRequiresItsExactRuleAndCanRetry() throws IOException {
        JsonNode materials = mapper.readTree(WORLD.replace("\"template_id\":\"dusk\"", "\"template_id\":\"dusk_starter\"")
                .replace("\"description\":\"守住关口\"",
                        "\"description\":\"守住关口\",\"engines\":{\"ap\":{\"template_id\":\"dusk_guard\"}}"))
                .path("world_template");
        when(catalog.findTemplate("dusk_starter")).thenReturn(Optional.of(new WorldTemplate(
                "dusk_starter", "dusk", List.of(), "暮野", materials, mapper.createObjectNode())));
        String definition = """
                {"engine_template_registrations":{"ap":[
                  {"template_id":"dusk_guard","entity_type":"skill","rule_type":"defend",
                   "parameters":{"ap_cost":1,"guard":3}}]}}
                """;
        String wrongCategory = """
                {"engine_template_registrations":{"ap":[
                  {"template_id":"dusk_guard","entity_type":"npc","rule_type":"dialogue","parameters":{}}]}}
                """;
        String wrongParameters = definition.replace("\"guard\":3", "\"guard\":-1");
        when(provider.respond(any())).thenReturn(response(wrongCategory), response(wrongParameters),
                response("{}"), response(wrongCategory), response(definition));
        ConfirmedTemplateRegistry registry = registry();
        WorldbookDefinition book = book("dusk_starter");
        registry.confirm("story", book, null);
        Path file = temporary.resolve("registry.json");
        String prior = Files.readString(file);

        assertThrows(NarrativeProviderException.class, () -> registry.confirm("ap-game", book, "ap"));
        assertEquals(prior, Files.readString(file));
        assertTrue(registry.rules("ap").isMissingNode());
        assertTrue(registry.world("ap-game").isMissingNode());

        registry.confirm("ap-game", book, "ap");
        assertEquals("dusk_guard", registry.world("ap-game").at("/skill_types/0/engines/ap/template_id").asText());
        assertEquals(1, registry.rules("ap").at("/dusk_guard/parameters/ap_cost").asInt());
        assertTrue(registry.world("story").at("/skill_types/0/engines").isMissingNode());
        ArgumentCaptor<NarrativePrompt> prompts = ArgumentCaptor.forClass(NarrativePrompt.class);
        verify(provider, times(5)).respond(prompts.capture());
        assertTrue(prompts.getAllValues().get(1).systemInstruction().contains("期望 skill，实际 npc"));
        assertTrue(prompts.getAllValues().get(2).systemInstruction().contains("parameters.guard 必须是 0 至 1000 的整数"));
        verify(catalog, times(3)).findTemplate("dusk_starter");
        verifyNoMoreInteractions(catalog);
    }

    /** 创建读取同一临时注册文件的实例。 */
    private ConfirmedTemplateRegistry registry() throws IOException {
        return new ConfirmedTemplateRegistry(mapper, provider, catalog, List.of(new ApTemplatePolicy(mapper)),
                temporary.resolve("registry.json").toString());
    }

    /** 创建测试用世界书，可指定精确模板或以类型作为模板标识。
     * @param templateId 明确指定的世界模板标识，空串表示使用世界类型
     */
    private static WorldbookDefinition book(String templateId) {
        return new WorldbookDefinition("dusk_book", "1.0", "暮野行旅", "dusk", templateId,
                "balanced", "暮色原野", List.of(), List.of(), List.of(), Map.of());
    }

    /** 包装提供器返回的模板 JSON。
     * @param json 模型声明的模板或规则增补 JSON 对象字符串
     */
    private static NarrativeResponse response(String json) {
        return new NarrativeResponse("", null, List.of(), Map.of("template", new InitializationValue.Text(json)));
    }
}
