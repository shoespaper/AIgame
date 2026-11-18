package com.gameneko.aigame.rulesets.ap;

import java.util.List;

/** AP-owned persistent records that do not belong to the generic game envelope. */
public record ApSystems(List<ApQuest> questLog, List<ApNpc> npcs, ApBattle.Snapshot battle) {
    public ApSystems {
        questLog = questLog == null ? List.of() : List.copyOf(questLog);
        npcs = npcs == null ? List.of() : List.copyOf(npcs);
    }

    public static ApSystems empty() { return new ApSystems(List.of(), List.of(), null); }

    public ApSystems withQuest(ApQuest quest) {
        var next = new java.util.ArrayList<>(questLog);
        next.add(quest);
        return new ApSystems(next, npcs, battle);
    }

    public ApSystems replaceQuest(ApQuest quest) {
        var next = questLog.stream().map(value -> value.questId().equals(quest.questId()) ? quest : value).toList();
        return new ApSystems(next, npcs, battle);
    }

    public ApSystems withQuests(List<ApQuest> quests) { return new ApSystems(quests, npcs, battle); }
    public ApSystems withNpc(ApNpc npc) { var next = new java.util.ArrayList<>(npcs); next.add(npc); return new ApSystems(questLog, next, battle); }
    public ApSystems replaceNpc(ApNpc npc) { return new ApSystems(questLog, npcs.stream().map(value -> value.npcId().equals(npc.npcId()) ? npc : value).toList(), battle); }
    public ApSystems withNpcs(List<ApNpc> values) { return new ApSystems(questLog, values, battle); }
    public ApSystems withBattle(ApBattle.Snapshot nextBattle) { return new ApSystems(questLog, npcs, nextBattle); }
}
