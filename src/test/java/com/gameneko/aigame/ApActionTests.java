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
    void experienceActionPreservesOverflowAndSupportsMultipleLevels() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), catalog.world("isekai_grey"));

        var resolution = runtime.resolve(initial, new ApRulesetRuntime.GrantExperience(350));
        var next = resolution.nextState();

        assertEquals(3, next.level());
        assertEquals(50, next.xp());
        assertEquals(300, next.xpToNext());
        assertEquals(2, resolution.events().getFirst().levelsGained());
        assertEquals(350, resolution.events().getFirst().amount());
    }

    @Test
    void experienceActionRejectsZeroOrNegativeAmounts() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), catalog.world("isekai_grey"));

        assertEquals(false, runtime.validate(initial, new ApRulesetRuntime.GrantExperience(0)).accepted());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.resolve(initial, new ApRulesetRuntime.GrantExperience(-1)));
    }

    @Test
    void learnsCatalogAbilityOnlyWhenPrerequisitesAreMet() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), catalog.world("isekai_grey"));

        var next = runtime.resolve(initial, new ApRulesetRuntime.LearnAbility("skill_offense"), 0,
                catalog.world("isekai_grey")).nextState();

        assertEquals(1, next.skills().size());
        assertEquals("skill_offense", next.skills().getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> runtime.resolve(next,
                new ApRulesetRuntime.LearnAbility("skill_offense"), 0, catalog.world("isekai_grey")));
    }

    @Test
    void equipsInventoryItemAndUsesPotionThroughTypedActions() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), catalog.world("isekai_grey"));
        var equipped = runtime.resolve(initial, new ApRulesetRuntime.EquipItem("短剑", "weapon"), 0,
                catalog.world("isekai_grey")).nextState();
        var damaged = equipped.withVitality(10, equipped.spCurrent());
        var healed = runtime.resolve(damaged, new ApRulesetRuntime.UseItem("potion"), 0,
                catalog.world("isekai_grey")).nextState();

        assertEquals("短剑", equipped.equipment().weapon());
        assertEquals(20, healed.hpCurrent());
        assertEquals(1, healed.resources().potions());
    }

    @Test
    void questRewardIsClaimedOnceAndAddsResourcesAndExperience() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), catalog.world("isekai_grey"));
        var next = runtime.resolve(initial, new ApRulesetRuntime.ClaimQuestReward("quest-1", 100, 5, 2, 1), 0,
                catalog.world("isekai_grey")).nextState();

        assertEquals(2, next.level());
        assertEquals(20, next.resources().copper());
        assertEquals(5, next.resources().rations());
        assertEquals(3, next.resources().potions());
        assertThrows(IllegalArgumentException.class, () -> runtime.resolve(next,
                new ApRulesetRuntime.ClaimQuestReward("quest-1", 1, 0, 0, 0), 0, catalog.world("isekai_grey")));
    }

    @Test
    void advanceDayAddsHungerWhenRationsAreMissing() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "hard"), catalog.world("isekai_grey"));
        var empty = initial.withResources(new ApRulesetRuntime.Resources(0, 0, 0));

        var resolution = runtime.resolve(empty, new ApRulesetRuntime.AdvanceDay());
        assertEquals(0, resolution.nextState().resources().rations());
        assertEquals("饥饿", resolution.nextState().statusEffects().getFirst().name());
    }

    @Test
    void existingStatusEffectsExpireDuringDailySettlement() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), catalog.world("isekai_grey"));
        var withStatus = initial.withStatusEffect(new ApRulesetRuntime.StatusEffect("中毒", 2, "HP -1"));

        var next = runtime.resolve(withStatus, new ApRulesetRuntime.AdvanceDay()).nextState();

        assertEquals(1, next.statusEffects().getFirst().duration());
    }

    @Test
    void deadCharactersCannotAdvanceTheDay() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"));
        var dead = initial.withVitality(0, initial.spCurrent());

        assertEquals(false, runtime.validate(dead, new ApRulesetRuntime.AdvanceDay()).accepted());
        assertThrows(IllegalArgumentException.class, () -> runtime.resolve(dead, new ApRulesetRuntime.AdvanceDay()));
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

        var updated = new GameActionService(repository, catalog).apply("demo", 0, new ApRulesetRuntime.AdvanceDay());

        assertEquals(1, updated.revision());
        assertEquals(2, updated.worldState().day());
        assertEquals(2, updated.worldState().lastTickDay());
        assertEquals(2, updated.rulesetState().resources().rations());
        assertEquals(GameInstance.Lifecycle.ACTIVE, updated.lifecycle());
    }

    @Test
    void applicationDailySettlementEscalatesThreatAtInterval() {
        AtomicReference<GameInstance<?>> saved = new AtomicReference<>();
        GameRepository repository = new GameRepository() {
            @Override public Optional<GameInstance<?>> load(String gameId) { return Optional.ofNullable(saved.get()); }
            @Override public void save(GameInstance<?> instance) { saved.set(instance); }
        };
        var instanceService = new GameInstanceService(new WorldInitializationService(catalog), catalog);
        var initial = instanceService.initialize("threat", InitializeWorldRequest.defaults("isekai_grey"));
        var world = new com.gameneko.aigame.engine.WorldState(initial.worldState().worldId(), initial.worldState().contentVersion(),
                0, initial.worldState().setting(), initial.worldState().settingDescription(), initial.worldState().location(),
                initial.worldState().currentRegion(), initial.worldState().mainQuest(), initial.worldState().day(),
                initial.worldState().time(), initial.worldState().season(), initial.worldState().weather(), initial.worldState().flags(),
                initial.worldState().threatLevel(), 0, 1, initial.worldState().threatHistory(), initial.worldState().timedEvents(),
                initial.worldState().incidentalEvents(), initial.worldState().lastTickDay(), false, initial.worldState().mainPlotStage(),
                initial.worldState().mainPlotStages(), initial.worldState().priceModifiers(), initial.worldState().originScene());
        var prepared = new GameInstance<>(initial.gameId(), initial.worldId(), initial.contentVersion(), initial.rulesetId(),
                initial.rulesetVersion(), initial.lifecycle(), 0, world, initial.rulesetState());
        repository.save(prepared);

        var updated = new GameActionService(repository, catalog).apply("threat", 0, new ApRulesetRuntime.AdvanceDay());

        assertEquals("Low", updated.worldState().threatLevel());
        assertEquals(0, updated.worldState().threatCounter());
        assertEquals(1, updated.worldState().threatHistory().size());
    }

    @Test
    void applicationExperienceActionPersistsLevelProgression() {
        AtomicReference<GameInstance<?>> saved = new AtomicReference<>();
        GameRepository repository = new GameRepository() {
            @Override public Optional<GameInstance<?>> load(String gameId) { return Optional.ofNullable(saved.get()); }
            @Override public void save(GameInstance<?> instance) { saved.set(instance); }
        };
        var instanceService = new GameInstanceService(new WorldInitializationService(catalog), catalog);
        repository.save(instanceService.initialize("xp", InitializeWorldRequest.defaults("isekai_grey")));

        var updated = new GameActionService(repository, catalog).apply(
                "xp", 0, new ApRulesetRuntime.GrantExperience(100));

        assertEquals(1, updated.revision());
        assertEquals(2, updated.rulesetState().level());
        assertEquals(0, updated.rulesetState().xp());
        assertEquals(200, updated.rulesetState().xpToNext());
    }
}
