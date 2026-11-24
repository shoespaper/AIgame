package com.gameneko.aigame.rulesets.ap;

import java.util.List;

/** AP-owned persistent records that do not belong to the generic game envelope. */
public record ApSystems(List<ApQuest> questLog, List<ApNpc> npcs, ApBattle.Snapshot battle, int threatDays, int threatStage) {
    public ApSystems {
        questLog = questLog == null ? List.of() : List.copyOf(questLog);
        npcs = npcs == null ? List.of() : List.copyOf(npcs);
    }

    /** 按已选难度结算一天的威胁增长。
     * @param interval 每一级威胁增长需要的游戏天数
     * @param lastStage 最高威胁等级的序号
     */
    public ApSystems advanceThreat(long interval, int lastStage) {
        int days = threatDays + 1;
        return days >= interval ? new ApSystems(questLog, npcs, battle, 0, Math.min(lastStage, threatStage + 1))
                : new ApSystems(questLog, npcs, battle, days, threatStage);
    }

    public static ApSystems empty() { return new ApSystems(List.of(), List.of(), null, 0, 0); }

    public ApSystems withQuest(ApQuest quest) {
        var next = new java.util.ArrayList<>(questLog);
        next.add(quest);
        return new ApSystems(next, npcs, battle, threatDays, threatStage);
    }

    public ApSystems replaceQuest(ApQuest quest) {
        var next = questLog.stream().map(value -> value.questId().equals(quest.questId()) ? quest : value).toList();
        return new ApSystems(next, npcs, battle, threatDays, threatStage);
    }

    public ApSystems withQuests(List<ApQuest> quests) { return new ApSystems(quests, npcs, battle, threatDays, threatStage); }
    public ApSystems withNpc(ApNpc npc) { var next = new java.util.ArrayList<>(npcs); next.add(npc); return new ApSystems(questLog, next, battle, threatDays, threatStage); }
    public ApSystems replaceNpc(ApNpc npc) { return new ApSystems(questLog, npcs.stream().map(value -> value.npcId().equals(npc.npcId()) ? npc : value).toList(), battle, threatDays, threatStage); }
    public ApSystems withNpcs(List<ApNpc> values) { return new ApSystems(questLog, values, battle, threatDays, threatStage); }
    public ApSystems withBattle(ApBattle.Snapshot nextBattle) { return new ApSystems(questLog, npcs, nextBattle, threatDays, threatStage); }
}
