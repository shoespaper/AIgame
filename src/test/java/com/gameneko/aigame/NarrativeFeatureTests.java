package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.narrative.LocalNarrativeProvider;
import com.gameneko.aigame.narrative.NarrativePrompt;
import com.gameneko.aigame.narrative.NarrativeProposalCandidate;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import com.gameneko.aigame.narrative.ProposalConversionService;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrativeFeatureTests {
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
    private final ProposalConversionService converter = new ProposalConversionService();

    @Test
    void localNarrationUsesOnlyTheReadOnlySnapshot() {
        var state = new ApRulesetRuntime().initialize(new ApRulesetRuntime.ApInitializationRequest("hero", 16, "", "swordsman", "adventure"), catalog.world("isekai_grey"));
        var snapshot = new NarrativeSnapshot(new NarrativeSnapshot.WorldView(4, "dawn", "spring", "clear", "town", "forest", "Low", List.of(), List.of(), List.of()),
                new NarrativeSnapshot.CharacterView(state.characterName(), state.archetype(), state.level(), state.xp(), state.xpToNext(), state.hpCurrent(), state.hpMax(), state.spCurrent(), state.spMax(),
                        new NarrativeSnapshot.Attributes(state.strength(), state.dexterity(), state.constitution(), state.intelligence(), state.wisdom(), state.charisma()),
                        new NarrativeSnapshot.Resources(state.resources().copper(), state.resources().rations(), state.resources().potions()), List.of(), List.of(), List.of(), List.of(), List.of(), null),
                List.of(new NarrativeSnapshot.ConfirmedEvent("ap", "experience", "hero", "", 20, "0")));
        var response = new LocalNarrativeProvider().respond(new NarrativePrompt("grounded", "look", snapshot, "en-US"));
        assertTrue(response.narration().contains("hero"));
        assertTrue(response.narration().contains("experience"));
        assertTrue(response.proposals().isEmpty());
    }

    @Test
    void engineOwnsProposalFieldAndValueValidation() {
        var world = catalog.world("isekai_grey");
        var engine = new com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime(world);
        var accepted = converter.convert(new NarrativeProposalCandidate("gain_experience", java.util.Map.of("amount", "40")), "game", 0, 0, 0, world, engine);
        assertTrue(accepted.converted());
        assertEquals("grant_experience", accepted.action().type());

        var bounded = converter.convert(new NarrativeProposalCandidate("gain_experience", java.util.Map.of("amount", "100000")), "game", 0, 0, 0, world, engine);
        assertFalse(bounded.converted());
        var extra = converter.convert(new NarrativeProposalCandidate("gain_experience", java.util.Map.of("amount", "40", "energy", "99")), "game", 0, 0, 0, world, engine);
        assertFalse(extra.converted());
    }

    @Test
    void itemAndTimedEventProposalsUseTypedServerOwnedDefinitions() {
        var world = catalog.world("isekai_grey");
        var engine = new com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime(world);
        var item = converter.convert(new NarrativeProposalCandidate("acquire_item", java.util.Map.of("itemName", "healing", "itemCategory", "healing", "itemTier", "common")), "game", 3, 0, 0, world, engine);
        assertTrue(item.converted());
        assertEquals("acquire_item", item.action().type());

        var event = converter.convert(new NarrativeProposalCandidate("schedule_event", java.util.Map.of("deadlineDays", "5", "eventId", "thread-1", "eventDescription", "A sealed door hums", "eventAnswer", "seal", "eventConsequence", "breach")), "game", 3, 0, 2, world);
        assertTrue(event.converted());
        assertTrue(event.worldAction() instanceof com.gameneko.aigame.engine.WorldAction.ScheduleEvent);

        var noEngine = converter.convert(new NarrativeProposalCandidate("gain_experience", java.util.Map.of("amount", "40")), "game", 3, 0, 0, world, null);
        assertFalse(noEngine.converted());
    }
}
