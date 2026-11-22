package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.narrative.DefaultNarrationService;
import com.gameneko.aigame.narrative.NarrativeRequest;
import com.gameneko.aigame.narrative.NarrativeResponse;
import com.gameneko.aigame.narrative.NarrativeSnapshot;
import com.gameneko.aigame.rulesets.ap.ApGameEngineRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证引擎动作清单由引擎侧暴露，且叙事层按清单校验 action 类型。 */
class NarrativeFeatureTests {
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());

    @Test
    void engineExposesItsActionCatalog() {
        var world = catalog.world("isekai_grey");
        var engine = new ApGameEngineRuntime(world);
        var actions = engine.supportedActions();
        assertFalse(actions.isEmpty());
        assertTrue(actions.stream().anyMatch(action -> action.type().equals("rest")));
        assertTrue(actions.stream().anyMatch(action -> action.type().equals("acquire_item")));
    }

    @Test
    void narrationRejectsActionOutsideEngineCatalog() {
        var world = catalog.world("isekai_grey");
        var engine = new ApGameEngineRuntime(world);
        var actions = engine.supportedActions();
        var service = new DefaultNarrationService(prompt -> new NarrativeResponse(
                "就绪。", new ActionIntent("socialize", "", List.of()), List.of("继续前进")));
        assertThrows(IllegalArgumentException.class, () -> service.narrate(request(), actions));
    }

    @Test
    void pureNarrationWithoutActionIsAccepted() {
        var world = catalog.world("isekai_grey");
        var engine = new ApGameEngineRuntime(world);
        var actions = engine.supportedActions();
        var service = new DefaultNarrationService(prompt -> new NarrativeResponse("夜色降临。", null, List.of("继续前进")));
        var response = service.narrate(request(), actions);
        assertTrue(response.action() == null);
    }

    private static NarrativeRequest request() {
        return new NarrativeRequest("test-game", 0, "观察四周", "zh-CN",
                new NarrativeSnapshot(new NarrativeSnapshot.WorldView(1, "dawn", "spring", "clear",
                        "村庄", "北境", "Low", List.of(), List.of(), List.of()), null, List.of()));
    }
}
