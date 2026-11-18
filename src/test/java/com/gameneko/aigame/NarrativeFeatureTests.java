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
    void conversionWhitelistsAndBoundsExperienceProposals() {
        var world = catalog.world("isekai_grey");
        var accepted = converter.convert(new NarrativeProposalCandidate("gain_experience", 40L, null, null, null, null, null, null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null), "game", 0, 0, world);
        assertTrue(accepted.converted());
        assertInstanceOf(ApRulesetRuntime.GrantExperience.class, accepted.action());

        var rejected = converter.convert(new NarrativeProposalCandidate("gain_experience", 100_000L, null, null, null, null, null, null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null), "game", 0, 0, world);
        assertFalse(rejected.converted());
        assertEquals("experience_out_of_range", rejected.rejection());
    }

    @Test
    void itemAndTimedEventProposalsUseTypedServerOwnedDefinitions() {
        var world = catalog.world("isekai_grey");
        var item = converter.convert(new NarrativeProposalCandidate("acquire_item", null, "healing", "healing", "common", null, null, null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null), "game", 3, 0, world);
        assertTrue(item.converted());
        assertInstanceOf(ApRulesetRuntime.AcquireItem.class, item.action());

        var event = converter.convert(new NarrativeProposalCandidate("schedule_event", null, null, null, null, null, null, null, null, List.of(), null, null, null, null, 5L, "thread-1", "A sealed door hums", "seal", "breach", null, null, null), "game", 3, 0, 2, world);
        assertTrue(event.converted());
        assertTrue(event.worldAction() instanceof com.gameneko.aigame.engine.WorldAction.ScheduleEvent);
    }
}
