package com.gameneko.aigame.narrative;

import com.gameneko.aigame.engine.GameInstance;
import com.gameneko.aigame.engine.WorldEvent;
import com.gameneko.aigame.rulesets.ap.ApBattle;
import com.gameneko.aigame.rulesets.ap.ApNpc;
import com.gameneko.aigame.rulesets.ap.ApQuest;
import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime;

import java.util.List;

/** Creates the deliberately small read-only view available to a narrative provider. */
public final class NarrativeSnapshotFactory {
    private NarrativeSnapshotFactory() {}

    public static NarrativeSnapshot from(GameInstance<ApRulesetRuntime.ApState> instance,
                                         List<ApRulesetRuntime.ApEvent> actionEvents,
                                         List<WorldEvent> confirmedWorldEvents) {
        var world = instance.worldState();
        var state = instance.rulesetState();
        var worldView = new NarrativeSnapshot.WorldView(world.day(), world.time(), world.season(), world.weather(),
                world.location(), world.currentRegion(), world.threatLevel(),
                world.deadlineEvents().stream().filter(e -> e.status() == com.gameneko.aigame.engine.TimedEvent.Status.ACTIVE).limit(12)
                        .map(e -> new NarrativeSnapshot.TimedEventView(e.id(), e.description(), e.daysRemaining(), e.status().name())).toList(),
                confirmedWorldEvents.stream().skip(Math.max(0, confirmedWorldEvents.size() - 8L)).toList().stream()
                        .map(e -> new NarrativeSnapshot.WorldEventView(e.day(), e.type().name(), e.description())).toList(),
                world.activePriceModifiers().values().stream()
                        .map(modifier -> new NarrativeSnapshot.PriceView(modifier.category().name(), modifier.percent())).toList());
        var battle = state.systems().battle();
        NarrativeSnapshot.BattleView battleView = battle == null ? null : new NarrativeSnapshot.BattleView(
                battle.round(), battle.currentActor().name(), battle.turnOrder().stream()
                .map(id -> battle.combatant(id).name()).toList(), battle.combatants().stream()
                .map(combatant -> new NarrativeSnapshot.CombatantView(combatant.name(), combatant.player(),
                        combatant.hp(), combatant.maxHp(), combatant.power(), combatant.mitigation(), combatant.currentAp())).toList());
        var character = new NarrativeSnapshot.CharacterView(state.characterName(), state.archetype(), state.level(),
                state.xp(), state.xpToNext(), state.hpCurrent(), state.hpMax(), state.spCurrent(), state.spMax(),
                new NarrativeSnapshot.Attributes(state.strength(), state.dexterity(), state.constitution(),
                        state.intelligence(), state.wisdom(), state.charisma()),
                new NarrativeSnapshot.Resources(state.resources().copper(), state.resources().rations(), state.resources().potions()),
                state.inventory().stream().limit(50).map(item -> new NarrativeSnapshot.InventoryEntry(item.name(), item.type(), item.quantity())).toList(),
                java.util.stream.Stream.concat(state.skills().stream(), state.spells().stream()).limit(40).map(ApRulesetRuntime.Ability::name).toList(),
                state.statusEffects().stream().limit(20).map(ApRulesetRuntime.StatusEffect::name).toList(),
                state.systems().questLog().stream().limit(30).map(NarrativeSnapshotFactory::questView).toList(),
                state.systems().npcs().stream().limit(40).map(NarrativeSnapshotFactory::npcView).toList(), battleView);
        var events = new java.util.ArrayList<NarrativeSnapshot.ConfirmedEvent>();
        actionEvents.stream().limit(20).forEach(event -> events.add(new NarrativeSnapshot.ConfirmedEvent("ap", event.type(), "player", "", event.amount(), Integer.toString(event.levelsGained()))));
        confirmedWorldEvents.stream().skip(Math.max(0, confirmedWorldEvents.size() - 8L)).forEach(event -> events.add(new NarrativeSnapshot.ConfirmedEvent("world", event.type().name(), "world", "", event.day(), event.description())));
        if (battle != null && !battle.log().isEmpty()) {
            var event = battle.log().getLast();
            events.add(new NarrativeSnapshot.ConfirmedEvent("battle", event.action().name(),
                    battle.combatant(event.actorId()).name(), event.targetId() == null ? "" : battle.combatant(event.targetId()).name(),
                    event.damage(), event.outcome()));
        }
        return new NarrativeSnapshot(worldView, character, events);
    }

    private static NarrativeSnapshot.QuestView questView(ApQuest quest) {
        return new NarrativeSnapshot.QuestView(quest.title(), quest.status().name(), quest.region(),
                quest.objectives().stream().map(objective -> objective.description() + " " + objective.progress() + "/" + objective.required()).toList());
    }

    private static NarrativeSnapshot.NpcView npcView(ApNpc npc) {
        return new NarrativeSnapshot.NpcView(npc.name(), npc.type(), npc.location(), npc.relationship(), npc.mood(), npc.status().name());
    }
}
