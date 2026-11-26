package com.gameneko.aigame;

import java.util.List;
import com.gameneko.aigame.engine.ActionIntent;
import com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.application.GameActionService;
import com.gameneko.aigame.application.GameInstanceService;
import com.gameneko.aigame.application.InitializeWorldRequest;
import com.gameneko.aigame.application.WorldInitializationService;
import com.gameneko.aigame.application.port.GameRepository;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.content.WorldDefinition;
import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.rulesets.ap.ApQuest;
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
                "旅人", 16, "未定", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));

        var resolution = runtime.resolve(initial, new ApRulesetRuntime.ConsumeRation());

        assertEquals(3, initial.resources().rations());
        assertEquals(2, resolution.nextState().resources().rations());
        assertEquals("consume_ration", resolution.events().getFirst().type());
    }

    @Test
    void experienceActionPreservesOverflowAndSupportsMultipleLevels() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));

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
                "旅人", 16, "未定", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));

        assertEquals(false, runtime.validate(initial, new ApRulesetRuntime.GrantExperience(0)).accepted());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.resolve(initial, new ApRulesetRuntime.GrantExperience(-1)));
    }

    @Test
    void learnsCatalogAbilityOnlyWhenPrerequisitesAreMet() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));

        var next = runtime.resolve(initial, new ApRulesetRuntime.LearnAbility("skill_offense"), 0,
                ApTemplateFixture.confirmed(catalog)).nextState();

        assertEquals(1, next.skills().size());
        assertEquals("skill_offense", next.skills().getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> runtime.resolve(next,
                new ApRulesetRuntime.LearnAbility("skill_offense"), 0, ApTemplateFixture.confirmed(catalog)));
    }

    @Test
    void equipsInventoryItemAndUsesPotionThroughTypedActions() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));
        var equipped = runtime.resolve(initial, new ApRulesetRuntime.EquipItem("短剑", "weapon"), 0,
                ApTemplateFixture.confirmed(catalog)).nextState();
        var damaged = equipped.withVitality(10, equipped.spCurrent());
        var healed = runtime.resolve(damaged, new ApRulesetRuntime.UseItem("potion"), 0,
                ApTemplateFixture.confirmed(catalog)).nextState();

        assertEquals("短剑", equipped.equipment().weapon());
        assertEquals(20, healed.hpCurrent());
        assertEquals(1, healed.resources().potions());
    }

    /** 验证完成任务会发放奖励定义中的资源和经验，并拒绝重复完成。 */
    @Test
    void completingQuestGrantsResourcesAndExperienceOnce() {
        ApRulesetRuntime runtime = new ApRulesetRuntime();
        WorldDefinition world = ApTemplateFixture.confirmed(catalog);
        ApRulesetRuntime.ApState initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), world);
        ApQuest quest = new ApQuest("quest-1", "商队委托", "", "forest", ApQuest.Status.AVAILABLE, List.of(),
                new ApQuest.Reward(100, 5, 2, 1), 1, 0, 0, "");
        ApRulesetRuntime.ApState offered = runtime.resolve(initial, new ApRulesetRuntime.OfferQuest(quest), 0, world).nextState();
        ApRulesetRuntime.ApState active = runtime.resolve(offered, new ApRulesetRuntime.AcceptQuest(quest.questId()), 0, world).nextState();
        ApRulesetRuntime.CompleteQuest complete = new ApRulesetRuntime.CompleteQuest(quest.questId());
        ApRulesetRuntime.ApState next = runtime.resolve(active, complete, 0, world).nextState();

        assertEquals(2, next.level());
        assertEquals(20, next.resources().copper());
        assertEquals(5, next.resources().rations());
        assertEquals(3, next.resources().potions());
        assertThrows(IllegalArgumentException.class, () -> runtime.resolve(next, complete, 0, world));
    }

    @Test
    void advanceDayAddsHungerWhenRationsAreMissing() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "hard"), ApTemplateFixture.confirmed(catalog));
        var empty = initial.withResources(new ApRulesetRuntime.Resources(0, 0, 0));

        var resolution = runtime.resolve(empty, new ApRulesetRuntime.AdvanceDay());
        assertEquals(0, resolution.nextState().resources().rations());
        assertEquals("饥饿", resolution.nextState().statusEffects().getFirst().name());
    }

    @Test
    void existingStatusEffectsExpireDuringDailySettlement() {
        var runtime = new ApRulesetRuntime();
        var initial = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest(
                "旅人", 16, "未定", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));
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
        var instanceService = new GameInstanceService(new WorldInitializationService(catalog), catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory());
        var initial = instanceService.initialize("demo", InitializeWorldRequest.defaults("isekai_grey"), "ap", apAnswers());
        repository.save(initial);

        @SuppressWarnings("unchecked") GameInstance<ApRulesetRuntime.ApState> updated = (GameInstance<ApRulesetRuntime.ApState>) (GameInstance<?>) new GameActionService(repository, catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory()).applyIntent("demo", 0, new com.gameneko.aigame.engine.ActionIntent("advance_day", "", java.util.List.of())).instance();

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
        var instanceService = new GameInstanceService(new WorldInitializationService(catalog), catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory());
        var initial = instanceService.initialize("threat", InitializeWorldRequest.defaults("isekai_grey"), "ap", apAnswers());
        repository.save(initial);
        GameActionService actions = new GameActionService(repository, catalog, new ClasspathGameEngineFactory());
        GameInstance<?> updated = initial;
        for (int day = 0; day < 7; day++) {
            updated = actions.applyIntent("threat", updated.revision(), new ActionIntent("advance_day", "", List.of())).instance();
        }
        ApRulesetRuntime.ApState state = (ApRulesetRuntime.ApState) updated.rulesetState();
        assertEquals(1, state.systems().threatStage());
        assertEquals(0, state.systems().threatDays());
    }

    @Test
    void applicationExperienceActionPersistsLevelProgression() {
        AtomicReference<GameInstance<?>> saved = new AtomicReference<>();
        GameRepository repository = new GameRepository() {
            @Override public Optional<GameInstance<?>> load(String gameId) { return Optional.ofNullable(saved.get()); }
            @Override public void save(GameInstance<?> instance) { saved.set(instance); }
        };
        var instanceService = new GameInstanceService(new WorldInitializationService(catalog), catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory());
        repository.save(instanceService.initialize("xp", InitializeWorldRequest.defaults("isekai_grey"), "ap", apAnswers()));

        @SuppressWarnings("unchecked") GameInstance<ApRulesetRuntime.ApState> updated = (GameInstance<ApRulesetRuntime.ApState>) (GameInstance<?>) new GameActionService(repository, catalog, new com.gameneko.aigame.infrastructure.ClasspathGameEngineFactory()).applyIntent("xp", 0,
                new com.gameneko.aigame.engine.ActionIntent("grant_experience", "", java.util.List.of(
                        new com.gameneko.aigame.engine.ActionIntent.Argument("amount", new com.gameneko.aigame.engine.InitializationValue.IntegerNumber(100))))).instance();

        assertEquals(1, updated.revision());
        assertEquals(2, updated.rulesetState().level());
        assertEquals(0, updated.rulesetState().xp());
        assertEquals(200, updated.rulesetState().xpToNext());
    }

    /** 构造 AP 初始化所需的显式测试答案。 */
    private static java.util.Map<String, com.gameneko.aigame.engine.InitializationValue> apAnswers() {
        return java.util.Map.of("characterName", new com.gameneko.aigame.engine.InitializationValue.Text("旅人"),
                "age", new com.gameneko.aigame.engine.InitializationValue.IntegerNumber(16),
                "gender", new com.gameneko.aigame.engine.InitializationValue.Text("未定"),
                "archetype", new com.gameneko.aigame.engine.InitializationValue.Text("swordsman"),
                "difficulty", new com.gameneko.aigame.engine.InitializationValue.Text("adventure"));
    }
}
