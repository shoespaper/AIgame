package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.GameActionService;
import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApActionTests {
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(
            new ObjectMapper(), new DefaultResourceLoader());

    @Test
    void typedActionsResolveWithoutMutatingTheOriginalState() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), catalog.world("isekai_grey"));

        var resolution = runtime.resolve(initial, new ApRulesetRuntime.ConsumeRation());

        assertEquals(3, initial.resources().rations());
        assertEquals(2, resolution.nextState().resources().rations());
        assertEquals("consume_ration", resolution.events().getFirst().type());
    }

    @Test
    void advanceDayRequiresRations() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "hard"), catalog.world("isekai_grey"));
        var empty = initial.withResources(new ApRulesetRuntime.Resources(0, 0, 0));

        assertEquals(false, runtime.validate(empty, new ApRulesetRuntime.AdvanceDay()).accepted());
        assertThrows(IllegalArgumentException.class, () -> runtime.resolve(empty, new ApRulesetRuntime.AdvanceDay()));
    }

    @Test
    void applicationActionAdvancesRevisionAndWorldDayBeforeSaving() {
        AtomicReference<GameInstance<?>> saved = new AtomicReference<>();
        GameRepository repository = new GameRepository() {
            @Override public Optional<GameInstance<?>> load(String gameId) { return Optional.ofNullable(saved.get()); }
            @Override public void save(GameInstance<?> instance) { saved.set(instance); }
        };
        var instanceService = new GameInstanceService(new WorldInitializationService(catalog), catalog);
        var initial = instanceService.initialize("demo", InitializeWorldRequest.defaults("isekai_grey"));
        repository.save(initial);

        var updated = new GameActionService(repository).apply("demo", 0, new ApRulesetRuntime.AdvanceDay());

        assertEquals(1, updated.revision());
        assertEquals(2, updated.worldState().day());
        assertEquals(2, updated.worldState().lastTickDay());
        assertEquals(2, updated.rulesetState().resources().rations());
        assertEquals(GameInstance.Lifecycle.ACTIVE, updated.lifecycle());
    }
}
