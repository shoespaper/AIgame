package com.gameneko.aigame.narrative;

import java.util.List;

/** 将动态世界事实、确认事件、引擎投影及世界书设定作为只读数据传给叙事模型。 */
public record NarrativeSnapshot(WorldView world, CharacterView character,
                                List<ConfirmedEvent> confirmedEvents, Object engineProjection,
                                com.gameneko.aigame.content.WorldbookDefinition worldbook, Object templateMaterials) {
    /** 创建不附带世界书资料的兼容快照。
     * @param world 世界动态视图
     * @param character 可空的旧角色视图
     * @param confirmedEvents 已确认事件
     * @param engineProjection 引擎中立的展示投影
     */
    public NarrativeSnapshot(WorldView world, CharacterView character, List<ConfirmedEvent> confirmedEvents, Object engineProjection) {
        this(world, character, confirmedEvents, engineProjection, null, null);
    }
    /** 创建不附带引擎和世界书资料的兼容快照。
     * @param world 世界动态视图
     * @param character 可空的旧角色视图
     * @param confirmedEvents 已确认事件
     */
    public NarrativeSnapshot(WorldView world, CharacterView character, List<ConfirmedEvent> confirmedEvents) {
        this(world, character, confirmedEvents, null);
    }
    /** 校验叙事快照并复制确认事件。
     * @param world 世界动态视图
     * @param character 可空的旧角色视图
     * @param confirmedEvents 已确认事件
     * @param engineProjection 引擎中立的展示投影
     * @param worldbook 可空的世界书叙事设定
     */
    public NarrativeSnapshot {
        if (world == null) throw new IllegalArgumentException("Narrative snapshot world is required");
        confirmedEvents = confirmedEvents == null ? List.of() : List.copyOf(confirmedEvents);
    }

    /** 为模型补充世界书叙事设定，避免开局后丢失故事基调与势力。
     * @param setting 世界书叙事资料
     * @return 附带叙事设定的新快照
     */
    public NarrativeSnapshot withWorldbook(com.gameneko.aigame.content.WorldbookDefinition setting) {
        return new NarrativeSnapshot(world, character, confirmedEvents, engineProjection, setting, templateMaterials);
    }

    /** 补充确认阶段登记的世界创作素材。
     * @param materials 世界类型对应的叙事素材
     */
    public NarrativeSnapshot withMaterials(Object materials) {
        return new NarrativeSnapshot(world, character, confirmedEvents, engineProjection, worldbook, materials);
    }

    public record WorldView(long day, String time,
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
