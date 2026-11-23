package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.content.WorldContentException;
import com.gameneko.aigame.engine.WorldState;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInitializationServiceTests {
    private final WorldInitializationService service = new WorldInitializationService(
            new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader()));

    @Test
    void initializesTheBaselineWorldState() {
        WorldState state = service.initialize("isekai_grey");

        assertEquals("isekai_grey", state.worldId());
        assertEquals("1.0", state.contentVersion());
        assertEquals(0, state.revision());
        assertEquals(1, state.day());
        assertEquals("清晨", state.time());
        assertEquals("Dormant", state.threatLevel());
        assertEquals(7, state.threatInterval());
        assertEquals("", state.originScene());
        assertNull(state.setting());
        assertNull(state.settingDescription());
        assertNull(state.location());
        assertNull(state.currentRegion());
        assertNull(state.mainQuest());
        assertEquals(0, state.threatCounter());
        assertEquals(0, state.lastTickDay());
        assertEquals(0, state.mainPlotStage());
        assertFalse(state.crisisTriggered());
        assertTrue(state.flags().isEmpty());
        assertTrue(state.threatHistory().isEmpty());
        assertTrue(state.timedEvents().isEmpty());
        assertTrue(state.incidentalEvents().isEmpty());
        assertTrue(state.mainPlotStages().isEmpty());
        assertTrue(state.priceModifiers().isEmpty());
    }

    @Test
    void preservesAnExplicitOriginScene() {
        String origin = "  白栎镇 ";
        assertEquals(origin, service.initialize("isekai_grey", origin).originScene());
    }

    @Test
    void rejectsUnsupportedWorlds() {
        assertThrows(WorldContentException.class, () -> service.initialize("missing"));
    }

    @Test
    void appliesWorldOverridesWithoutInterpretingNarrativeTextAsEnums() {
        WorldState state = service.initialize(new InitializeWorldRequest("isekai_grey",
                "灰烬界", "大陆", "幽篁古道", "forest",
                "辰时", "寻找裂隙", "本地猎户"));
        assertEquals("灰烬界", state.setting());
        assertEquals("大陆", state.settingDescription());
        assertEquals("幽篁古道", state.location());
        assertEquals("forest", state.currentRegion());
        assertEquals("辰时", state.time());
        assertEquals("寻找裂隙", state.mainQuest());
        assertEquals("本地猎户", state.originScene());
        assertNull(service.initialize("isekai_grey").location());
    }

    @Test
    void rejectsUnknownRegionsInsteadOfAcceptingPlaceNamesAsRegionIds() {
        assertThrows(IllegalArgumentException.class, () -> service.initialize(new InitializeWorldRequest(
                "isekai_grey", null, null, null, "幽篁古道", null, null, null)));
    }
}
