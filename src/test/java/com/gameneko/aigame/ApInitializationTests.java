package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApInitializationTests {
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(
            new ObjectMapper(), new DefaultResourceLoader());
    private final ApRulesetRuntime runtime = new ApRulesetRuntime();

    @Test
    void initializesSwordsmanUsingPythonBaselineValues() {
        var state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "阿尔文", 16, "男", "swordsman", "adventure"), catalog.world("isekai_grey"));

        assertEquals(14, state.strength());
        assertEquals(12, state.constitution());
        assertEquals(32, state.hpMax());
        assertEquals(10, state.spMax());
        assertEquals(15, state.resources().copper());
        assertEquals(3, state.resources().rations());
        assertEquals(2, state.resources().potions());
        assertEquals("短剑", state.equipment().weapon());
        assertNotNull(state.uniqueTalent());
        assertFalse(state.uniqueTalent().awakened());
    }

    @Test
    void difficultyChangesOnlyInitialResourcesInCharacterState() {
        var state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "mage", "hard"), catalog.world("isekai_grey"));

        assertEquals(16, state.intelligence());
        assertEquals(14, state.spMax());
        assertEquals(5, state.resources().copper());
        assertEquals(2, state.resources().rations());
        assertEquals(1, state.resources().potions());
    }

    @Test
    void worldCatalogExposesNamedAbilityDefinitionsForServerValidation() {
        var world = catalog.world("isekai_grey");

        assertEquals("skill", world.abilities().get("skill_offense").kind());
        assertEquals(12, world.abilities().get("skill_offense").prerequisites().get("STR"));
        assertEquals("spell", world.abilities().get("spell_light").kind());
    }
}
