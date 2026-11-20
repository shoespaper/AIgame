package com.gameneko.aigame.narrative;

import java.util.List;

/** Read-only facts supplied to a narrator after a ruleset resolution. */
public record NarrativeSnapshot(WorldView world, CharacterView character,
                                List<ConfirmedEvent> confirmedEvents, Object engineProjection) {
    public NarrativeSnapshot(WorldView world, CharacterView character, List<ConfirmedEvent> confirmedEvents) {
        this(world, character, confirmedEvents, null);
    }
    public NarrativeSnapshot {
        if (world == null) throw new IllegalArgumentException("Narrative snapshot world is required");
        confirmedEvents = confirmedEvents == null ? List.of() : List.copyOf(confirmedEvents);
    }

    public record WorldView(long day, String time, String season, String weather,
                            String location, String region, String threatLevel,
                            List<TimedEventView> deadlines, List<WorldEventView> recentEvents,
                            List<PriceView> prices) {
        public WorldView {
            deadlines = deadlines == null ? List.of() : List.copyOf(deadlines);
            recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
            prices = prices == null ? List.of() : List.copyOf(prices);
        }
    }

    public record CharacterView(String name, String archetype, int level, long experience,
                                long experienceToNext, int hp, int maxHp, int sp, int maxSp,
                                Attributes attributes, Resources resources,
                                List<InventoryEntry> inventory, List<String> abilities,
                                List<String> statuses, List<QuestView> quests, List<NpcView> npcs,
                                BattleView battle) {
        public CharacterView {
            inventory = inventory == null ? List.of() : List.copyOf(inventory);
            abilities = abilities == null ? List.of() : List.copyOf(abilities);
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            quests = quests == null ? List.of() : List.copyOf(quests);
            npcs = npcs == null ? List.of() : List.copyOf(npcs);
        }
    }

    public record Attributes(int strength, int dexterity, int constitution,
                             int intelligence, int wisdom, int charisma) {}
    public record Resources(long copper, long rations, long potions) {}
    public record InventoryEntry(String name, String category, int quantity) {}
    public record QuestView(String title, String status, String region, List<String> objectives) {
        public QuestView { objectives = objectives == null ? List.of() : List.copyOf(objectives); }
    }
    public record NpcView(String name, String type, String location, int relationship,
                          int mood, String status) {}
    public record BattleView(int round, String currentActor, List<String> initiativeOrder,
                             List<CombatantView> combatants) {
        public BattleView {
            initiativeOrder = initiativeOrder == null ? List.of() : List.copyOf(initiativeOrder);
            combatants = combatants == null ? List.of() : List.copyOf(combatants);
        }
    }
    public record CombatantView(String name, boolean player, int hp, int maxHp,
                                int power, int mitigation, int actionPoints) {}
    public record TimedEventView(String id, String description, long daysRemaining, String status) {}
    public record WorldEventView(long day, String type, String description) {}
    public record PriceView(String category, int multiplierPercent) {}
    public record ConfirmedEvent(String domain, String type, String subject,
                                 String target, long amount, String outcome) {}
}
