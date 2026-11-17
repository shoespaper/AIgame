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
        assertEquals("\u6e05\u6668", state.time());
        assertEquals("Dormant", state.threatLevel());
        assertEquals(7, state.threatInterval());
        assertEquals("", state.originScene());
        assertNull(state.setting());
        assertNull(state.settingDescription());
        assertNull(state.location());
        assertNull(state.currentRegion());
        assertNull(state.mainQuest());
        assertNull(state.season());
        assertNull(state.weather());
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
        String origin = "  \u767d\u680e\u9547 ";
        assertEquals(origin, service.initialize("isekai_grey", origin).originScene());
    }

    @Test
    void rejectsUnsupportedWorlds() {
        assertThrows(WorldContentException.class, () -> service.initialize("missing"));
    }

    @Test
    void appliesWorldOverridesWithoutInterpretingNarrativeTextAsEnums() {
        WorldState state = service.initialize(new InitializeWorldRequest("isekai_grey",
                "\u7070\u70ec\u754c", "\u5927\u9646", "\u5e7d\u7bc1\u53e4\u9053", "forest",
                "\u9634", "\u79cb", "\u8fb0\u65f6", "\u5bfb\u627e\u88c2\u9699", "\u672c\u5730\u730e\u6237"));
        assertEquals("\u7070\u70ec\u754c", state.setting());
        assertEquals("\u5927\u9646", state.settingDescription());
        assertEquals("\u5e7d\u7bc1\u53e4\u9053", state.location());
        assertEquals("forest", state.currentRegion());
        assertEquals("\u9634", state.weather());
        assertEquals("\u79cb", state.season());
        assertEquals("\u8fb0\u65f6", state.time());
        assertEquals("\u5bfb\u627e\u88c2\u9699", state.mainQuest());
        assertEquals("\u672c\u5730\u730e\u6237", state.originScene());
        assertNull(service.initialize("isekai_grey").location());
    }

    @Test
    void rejectsUnknownRegionsInsteadOfAcceptingPlaceNamesAsRegionIds() {
        assertThrows(IllegalArgumentException.class, () -> service.initialize(new InitializeWorldRequest(
                "isekai_grey", null, null, null, "\u5e7d\u7bc1\u53e4\u9053", null, null, null, null, null)));
    }
}
