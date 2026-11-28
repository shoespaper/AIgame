package com.gameneko.aigame.rulesets.ap;

import com.gameneko.aigame.rulesets.ap.ApRulesetRuntime.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** 保存 NPC 的身份、好感、日程、互动历史与交易资产。 */
public record ApNpc(String npcId, String name, String type, String personality, String location,
                    int relationship, Status status, List<ScheduleEntry> schedule,
                    List<LifeEvent> lifeEvents, List<Interaction> interactions,
                    boolean canTrain, boolean canCombat, boolean sellsItems, boolean givesQuests,
                    String scheduleTemplate, List<Item> inventory, long copper) {
    /** NPC 当前的可互动状态。 */
    public enum Status { ACTIVE, DEAD, MISSING }
    /** 互动对好感的影响方向。 */
    public enum InteractionResult { POSITIVE, NEUTRAL, NEGATIVE }

    /** 校验标识和资产，并复制集合以保持 NPC 状态不可变。 */
    public ApNpc {
        if (npcId == null || npcId.isBlank()) throw new IllegalArgumentException("NPC id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("NPC name is required");
        if (copper < 0) throw new IllegalArgumentException("NPC 铜币余额不能为负数");
        type = type == null ? "other" : type;
        personality = personality == null ? "" : personality;
        location = location == null ? "" : location;
        scheduleTemplate = scheduleTemplate == null ? "" : scheduleTemplate;
        relationship = Math.max(-100, Math.min(100, relationship));
        status = status == null ? Status.ACTIVE : status;
        schedule = schedule == null ? List.of() : List.copyOf(schedule);
        lifeEvents = lifeEvents == null ? List.of() : List.copyOf(lifeEvents);
        interactions = interactions == null ? List.of() : List.copyOf(interactions);
        inventory = inventory == null ? List.of() : List.copyOf(inventory);
    }

    /** 记录互动并应用好感变化。
     * @param day 互动发生的游戏日
     * @param action 互动类型
     * @param result 互动结果方向
     * @param relationshipDelta 本次好感变化量
     */
    public ApNpc interact(long day, String action, InteractionResult result, int relationshipDelta) {
        if (status != Status.ACTIVE) throw new IllegalArgumentException("NPC is not available for interaction");
        List<Interaction> history = new ArrayList<>(interactions);
        history.add(new Interaction(day, action, result, relationshipDelta));
        return copy(relationship + relationshipDelta, status, lifeEvents, history, inventory, copper);
    }

    /** 在日结时让活跃 NPC 的好感缓慢回归中性。
     * @param day 当前游戏日
     * @param seed 好感变化的随机种子
     */
    public ApNpc dailyTick(long day, long seed) {
        if (status != Status.ACTIVE) return this;
        SplittableRandom random = new SplittableRandom(seed);
        int nextRelationship = relationship > 0 ? Math.max(0, relationship - random.nextInt(0, 2))
                : relationship < 0 ? Math.min(0, relationship + random.nextInt(0, 2)) : 0;
        return copy(nextRelationship, status, lifeEvents, interactions, inventory, copper);
    }

    /** 追加一条 NPC 生活事件。
     * @param event 已确认的生活事件
     */
    public ApNpc withLifeEvent(LifeEvent event) {
        List<LifeEvent> next = new ArrayList<>(lifeEvents);
        next.add(event);
        return copy(relationship, status, next, interactions, inventory, copper);
    }

    /** 更新 NPC 状态。
     * @param nextStatus 新的可互动状态
     */
    public ApNpc withStatus(Status nextStatus) {
        return copy(relationship, nextStatus, lifeEvents, interactions, inventory, copper);
    }

    /** 应用一次已计算的交易结果。
     * @param nextInventory 交易后的物品库存
     * @param nextCopper 交易后的非负铜币余额
     */
    public ApNpc withAssets(List<Item> nextInventory, long nextCopper) {
        return copy(relationship, status, lifeEvents, interactions, nextInventory, nextCopper);
    }

    /** 复制 NPC 并替换变更过的状态。
     * @param nextRelationship 更新后的好感
     * @param nextStatus 更新后的状态
     * @param nextLifeEvents 更新后的生活事件
     * @param nextInteractions 更新后的互动历史
     * @param nextInventory 更新后的库存
     * @param nextCopper 更新后的铜币余额
     */
    private ApNpc copy(int nextRelationship, Status nextStatus, List<LifeEvent> nextLifeEvents,
                       List<Interaction> nextInteractions, List<Item> nextInventory, long nextCopper) {
        return new ApNpc(npcId, name, type, personality, location, nextRelationship, nextStatus, schedule,
                nextLifeEvents, nextInteractions, canTrain, canCombat, sellsItems, givesQuests, scheduleTemplate,
                nextInventory, nextCopper);
    }

    /** 一条 NPC 日程。 */
    public record ScheduleEntry(String time, String location, String activity) {
        public ScheduleEntry {
            time = time == null ? "" : time;
            location = location == null ? "" : location;
            activity = activity == null ? "" : activity;
        }
    }

    /** 一条 NPC 生活事件。 */
    public record LifeEvent(long day, String description) {
        public LifeEvent { description = description == null ? "" : description; }
    }

    /** 一次 NPC 互动及其好感结果。 */
    public record Interaction(long day, String action, InteractionResult result, int relationshipDelta) {
        public Interaction {
            if (day < 0) throw new IllegalArgumentException("Interaction day must not be negative");
            if (action == null || action.isBlank()) throw new IllegalArgumentException("Interaction action is required");
            result = result == null ? InteractionResult.NEUTRAL : result;
        }
    }
}
