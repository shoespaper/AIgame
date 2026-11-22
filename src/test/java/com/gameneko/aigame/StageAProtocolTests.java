package com.gameneko.aigame;

import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.GameSelection;
import com.gameneko.aigame.engine.InitializationField;
import com.gameneko.aigame.engine.InitializationSchema;
import com.gameneko.aigame.engine.InitializationValue;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.narrative.NarrativeInitializationQuestion;
import com.gameneko.aigame.narrative.NarrativeParseCandidate;
import com.gameneko.aigame.narrative.NarrativeResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StageAProtocolTests {
    @Test
    void selectionAllowsAnOptionalEngine() {
        assertFalse(new GameSelection("isekai_grey", null).hasEngine());
        assertEquals("ap", new GameSelection("isekai_grey", " ap ").engineId());
    }

    @Test
    void schemaRejectsUnknownFieldsAndInvalidValues() {
        var schema = new InitializationSchema(List.of(
                new InitializationField("name", "Name", InitializationField.ValueType.TEXT, true, List.of(), null, null, ""),
                new InitializationField("age", "Age", InitializationField.ValueType.INTEGER, true, List.of(), 1L, 120L, "")));
        var result = schema.validate(Map.of(
                "name", new InitializationValue.Text("旅人"),
                "age", new InitializationValue.IntegerNumber(0),
                "energy", new InitializationValue.IntegerNumber(10)));
        assertFalse(result.accepted());
        assertEquals("field_not_supported", result.errors().get("energy"));
        assertEquals("field_out_of_range", result.errors().get("age"));
    }

    @Test
    void noEngineInstanceHasNoRulesetIdentity() {
        var world = new WorldState("world", "v1", 0, "name", "desc", "town", "town", "", 1,
                "morning", "spring", "clear", Map.of(), "Dormant", 0, 1, List.of(), List.of(), List.of(),
                0, false, 0, List.of(), Map.of(), "");
        var instance = GameInstance.narrativeOnly("game", world);
        assertNull(instance.rulesetId());
        assertNull(instance.rulesetVersion());
        assertNull(instance.rulesetState());
    }

    @Test
    void protocolCarriesQuestionsFreeTextCandidatesAndAction() {
        var response = new NarrativeResponse("开始", new ActionIntent("rest", "", List.of()),
                List.of("继续前行", "查看四周"),
                List.of(new NarrativeInitializationQuestion("name", "名字？", List.of())),
                List.of(new NarrativeParseCandidate("name", new InitializationValue.Text("小明"), 0.95)));
        assertEquals("rest", response.action().type());
        assertEquals(2, response.options().size());
        assertEquals(1, response.initializationQuestions().size());
        assertEquals(0.95, response.parseCandidates().get(0).confidence());
    }
}
