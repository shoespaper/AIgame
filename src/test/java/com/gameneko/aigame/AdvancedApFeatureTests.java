package com.gameneko.aigame;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameneko.aigame.content.ClasspathContentCatalog;
import com.gameneko.aigame.engine.PriceModifier;
import com.gameneko.aigame.engine.TimedEvent;
import com.gameneko.aigame.engine.WorldState;
import com.gameneko.aigame.rulesets.ap.ApBattle;
import com.gameneko.aigame.rulesets.ap.ApItemEffect;
import com.gameneko.aigame.rulesets.ap.ApNpc;
import com.gameneko.aigame.rulesets.ap.ApQuest;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdvancedApFeatureTests {
    private final ClasspathContentCatalog catalog = new ClasspathContentCatalog(new ObjectMapper(), new DefaultResourceLoader());
    private final ApRulesetRuntime runtime = new ApRulesetRuntime();

    @Test
    void complexItemEffectsAreAppliedAndConsumed() {
        var state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest("hero", 16, "", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));
        var item = new ApRulesetRuntime.Item("tonic", "consumable", "", 1,
                new ApItemEffect(7, 3, true, List.of(new ApItemEffect.StatusApplication("focus", 2, "")), 0, 0, 0), 5, 5);
        state = state.withInventory(List.of(item)).withVitality(10, 1).withStatusEffect(new ApRulesetRuntime.StatusEffect("poison", 2, "HP -1"));
        var next = runtime.resolve(state, new ApRulesetRuntime.UseItem("tonic"), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        assertEquals(17, next.hpCurrent());
        assertEquals(4, next.spCurrent());
        assertEquals(1, next.statusEffects().size());
        assertEquals("focus", next.statusEffects().getFirst().name());
        assertEquals(0, next.inventory().size());
    }

    @Test
    void questLifecycleRequiresObjectivesAndPaysOnce() {
        var state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest("hero", 16, "", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));
        var quest = new ApQuest("q1", "Clear", "", "forest", ApQuest.Status.AVAILABLE,
                List.of(new ApQuest.Objective("wolves", "", 0, 2)), new ApQuest.Reward(50, 7, 1, 0), 1, 5, 0, "");
        state = runtime.resolve(state, new ApRulesetRuntime.OfferQuest(quest), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        state = runtime.resolve(state, new ApRulesetRuntime.AcceptQuest("q1"), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        state = runtime.resolve(state, new ApRulesetRuntime.ProgressQuest("q1", 0, 2), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        state = runtime.resolve(state, new ApRulesetRuntime.CompleteQuest("q1"), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        assertEquals(ApQuest.Status.COMPLETED, state.systems().questLog().getFirst().status());
        assertEquals(22, state.resources().copper());
        var completed = state;
        assertThrows(IllegalArgumentException.class, () -> runtime.resolve(completed, new ApRulesetRuntime.CompleteQuest("q1"), 0, ApTemplateFixture.confirmed(catalog)));
    }

    @Test
    void npcRelationshipIsBoundedAndInteractionIsRecorded() {
        org.junit.jupiter.api.Assertions.assertTrue(ApTemplateFixture.confirmed(catalog).npcTypes().containsKey("merchant"));
        var state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest("hero", 16, "", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));
        state = runtime.resolve(state, new ApRulesetRuntime.RegisterNpc("n1", "Mara", "merchant", "town", 2), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        for (int i = 0; i < 30; i++) state = runtime.resolve(state, new ApRulesetRuntime.InteractNpc("n1", "trade", ApNpc.InteractionResult.POSITIVE, i, 1), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        assertEquals(100, state.systems().npcs().getFirst().relationship());
        assertEquals(30, state.systems().npcs().getFirst().interactions().size());
    }

    /** 验证按指定怪物素材开战后使用先攻顺序和整数减伤。 */
    @Test
    void battleUsesInitiativeAndIntegerMitigationFormula() {
        var state = runtime.initialize(new ApRulesetRuntime.ApInitializationRequest("hero", 16, "", "swordsman", "adventure"), ApTemplateFixture.confirmed(catalog));
        ApRulesetRuntime.ApState started = runtime.resolve(state, new ApRulesetRuntime.StartBattle("test", "high"), 0,
                ApTemplateFixture.confirmed(catalog)).nextState();
        var battle = started.systems().battle();
        assertEquals(2, battle.combatants().size());
        assertEquals(battle.turnOrder().getFirst(), battle.currentActor().id());
        var player = battle.combatants().stream().filter(ApBattle.Combatant::player).findFirst().orElseThrow();
        var enemy = battle.combatants().stream().filter(c -> !c.player()).findFirst().orElseThrow();
        assertEquals("high", enemy.monsterType());
        var acted = runtime.resolve(started, new ApRulesetRuntime.BattleAction(ApBattle.ActionKind.ATTACK, enemy.id(), null, null), 0, ApTemplateFixture.confirmed(catalog)).nextState();
        assertEquals(1, acted.systems().battle().log().size());
        assertEquals(player.currentAp() - 2, acted.systems().battle().combatant(player.id()).currentAp());
    }

    @Test
    void worldDeadlineAndPriceAreTypedAndDeterministic() {
        WorldState state = new WorldState("w", "1", 0, null, null, null, null, null, 1, "",
                Map.of(), "", List.of(), List.of(), Map.of(), List.of());
        TimedEvent event = new TimedEvent("e1", "plot", "desc", "answer", "late", 0, 3, 2,
                TimedEvent.Status.ACTIVE, "");
        state = state.schedule(event).resolveTimedEvent("e1", "done");
        assertEquals(TimedEvent.Status.RESOLVED, state.deadlineEvents().getFirst().status());
        state = state.withPriceModifiers(Map.of(PriceModifier.Category.ALL, new PriceModifier("all", 125)));
        assertEquals(125, state.price(100, PriceModifier.Category.FOOD));
    }
}
