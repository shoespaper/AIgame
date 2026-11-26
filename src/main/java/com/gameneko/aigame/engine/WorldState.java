package com.gameneko.aigame.engine;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 保存引擎中立的权威世界事实、事件和按访问顺序去重的地点记录。
 * @param worldId 世界书稳定标识
 * @param contentVersion 世界内容版本
 * @param revision 目标世界修订号
 * @param setting 世界展示名称，可空
 * @param settingDescription 世界描述，可空
 * @param location 具体地点自由文本，可空
 * @param currentRegion GM 给出的当前区域叙事文本，可空
 * @param mainQuest 主线方向，可空
 * @param day 当前世界天数，以天为单位
 * @param time 当前时间描述，可空
 * @param flags 世界布尔标记映射
 * @param originScene 开场场景描述
 * @param visitedLocations 去重且保持访问顺序的地点列表，可空时按空列表处理
 * @param deadlineEvents 类型化期限事件列表，可空
 * @param worldEvents 历史世界事件列表，可空
 * @param activePriceModifiers 有效价格百分比映射，可空
 */
public record WorldState(
        String worldId,
        String contentVersion,
        long revision,
        String setting,
        String settingDescription,
        String location,
        String currentRegion,
        String mainQuest,
        long day,
        String time,
        Map<String, Boolean> flags,
        String originScene,
        List<TimedEvent> deadlineEvents,
        List<WorldEvent> worldEvents,
        Map<PriceModifier.Category, PriceModifier> activePriceModifiers,
        List<String> visitedLocations
) implements GameState {
    /** 复制集合并为空缺的事件与地点记录提供默认值。 */
    public WorldState {
        visitedLocations = visitedLocations == null ? List.of() : List.copyOf(new LinkedHashSet<>(visitedLocations));
        flags = Map.copyOf(flags);
        deadlineEvents = deadlineEvents == null ? List.of() : List.copyOf(deadlineEvents);
        worldEvents = worldEvents == null ? List.of() : List.copyOf(worldEvents);
        activePriceModifiers = activePriceModifiers == null ? Map.of() : Map.copyOf(activePriceModifiers);
    }

    /** 纯合并已由应用层校验的世界声明，不推进修订号；地点自动去重新增到访问记录。
     * @param updates 已校验的类型化世界字段，region 为区域叙事文本，visitedLocations 的 text 值为一个地点
     * @return 合并后的世界快照
     */
    public WorldState applyWorldUpdates(Map<String, InitializationValue> updates) {
        String nextLocation = updatedText(updates, "location", location);
        LinkedHashSet<String> visited = new LinkedHashSet<>(visitedLocations);
        if (updates.get("location") instanceof InitializationValue.Text text && !text.value().isBlank()) visited.add(text.value());
        if (updates.get("visitedLocations") instanceof InitializationValue.Text text && !text.value().isBlank()) visited.add(text.value());
        return new WorldState(worldId, contentVersion, revision, updatedText(updates, "name", setting),
                updatedText(updates, "description", settingDescription), nextLocation,
                updatedText(updates, "region", currentRegion), updatedText(updates, "mainQuest", mainQuest),
                day, updatedText(updates, "time", time), flags, updatedText(updates, "originScene", originScene),
                deadlineEvents, worldEvents, activePriceModifiers, List.copyOf(visited));
    }

    /** 读取声明中的文本标量，缺失或类型不符时保留当前值。
     * @param updates 已校验的世界声明
     * @param field 协议字段名
     * @param current 当前字段值，可空
     * @return 覆盖后的文本值
     */
    private static String updatedText(Map<String, InitializationValue> updates, String field, String current) {
        return updates.get(field) instanceof InitializationValue.Text text ? text.value() : current;
    }

    /** 注册标识唯一且已生效的期限事件。
     * @param event 待注册的有效期限事件
     */
    public WorldState schedule(TimedEvent event) {
        List<TimedEvent> next = new ArrayList<>(deadlineEvents);
        if (next.stream().anyMatch(value -> value.id().equals(event.id()))) throw new WorldActionRejected("Timed event already exists");
        if (event.status() != TimedEvent.Status.ACTIVE || event.registeredDay() > day) throw new WorldActionRejected("Timed event must be active and registered no later than today");
        next.add(event);
        return withTimeline(day, next, worldEvents, activePriceModifiers);
    }

    /** 更新修订号并保留其余世界事实和访问记录。
     * @param nextRevision 下一权威修订号
     */
    public WorldState withRevision(long nextRevision) {
        return new WorldState(worldId, contentVersion, nextRevision, setting, settingDescription, location,
                currentRegion, mainQuest, day, time, flags, originScene, deadlineEvents, worldEvents,
                activePriceModifiers, visitedLocations);
    }

    /** 解决指定有效事件并记录结果。
     * @param eventId 待解决事件的唯一标识
     * @param outcome 事件解决结果
     */
    public WorldState resolveTimedEvent(String eventId, String outcome) {
        if (deadlineEvents.stream().noneMatch(event -> event.id().equals(eventId) && event.status() == TimedEvent.Status.ACTIVE)) throw new WorldActionRejected("Active timed event not found");
        List<TimedEvent> next = deadlineEvents.stream().map(event -> event.id().equals(eventId) ? event.resolve(day, outcome) : event).toList();
        return withTimeline(day, next, worldEvents, activePriceModifiers);
    }

    /** 表示当前世界状态拒绝玩家提交的世界动作。 */
    public static final class WorldActionRejected extends IllegalArgumentException {
        /** 创建世界动作拒绝。
         * @param message 可反馈给玩家的拒绝原因
         */
        private WorldActionRejected(String message) {
            super(message);
        }
    }

    /** 依次应用全局和分类价格百分比计算最终价格。
     * @param basePrice 非负基础价格，单位与调用方货币一致
     * @param category 商品价格分类
     */
    public long price(long basePrice, PriceModifier.Category category) {
        if (basePrice < 0) throw new IllegalArgumentException("Base price must not be negative");
        BigInteger percent = BigInteger.valueOf(100);
        PriceModifier global = activePriceModifiers.get(PriceModifier.Category.ALL);
        if (global != null) percent = percent.multiply(BigInteger.valueOf(global.percent())).divide(BigInteger.valueOf(100));
        PriceModifier specific = activePriceModifiers.get(category);
        if (specific != null) percent = percent.multiply(BigInteger.valueOf(specific.percent())).divide(BigInteger.valueOf(100));
        return BigInteger.valueOf(basePrice).multiply(percent).divide(BigInteger.valueOf(100)).longValueExact();
    }

    /** 推进一天，确认本日到期的叙事事件，移除过期价格修正。
     */
    public WorldState advanceDay() {
        long nextDay = day + 1;
        List<TimedEvent> nextDeadlines = new ArrayList<>(deadlineEvents.size());
        List<WorldEvent> nextEvents = new ArrayList<>(worldEvents);
        for (TimedEvent event : deadlineEvents) {
            TimedEvent updated = event.tick(nextDay);
            nextDeadlines.add(updated);
            if (event.status() == TimedEvent.Status.ACTIVE && updated.status() == TimedEvent.Status.EXPIRED) {
                nextEvents.add(new WorldEvent(event.id() + "-expired", nextDay, WorldEvent.Type.DEADLINE,
                        event.consequence(), event.materialId()));
            }
        }
        Map<PriceModifier.Category, PriceModifier> nextPrices = new EnumMap<>(PriceModifier.Category.class);
        nextPrices.putAll(activePriceModifiers);
        nextPrices.values().removeIf(modifier -> modifier.expiresDay() > 0 && nextDay >= modifier.expiresDay());
        return withTimeline(nextDay, nextDeadlines, nextEvents, nextPrices);
    }

    /** 替换有效价格修正并保留世界事件事实。
     * @param nextPrices 按商品分类索引的价格修正
     */
    public WorldState withPriceModifiers(Map<PriceModifier.Category, PriceModifier> nextPrices) {
        return withTimeline(day, deadlineEvents, worldEvents, nextPrices);
    }

    /** 替换世界时间线并保留其他叙事状态。
     * @param nextDay 更新后的世界天数
     * @param nextDeadlines 更新后的期限事件
     * @param nextEvents 更新后的世界事件
     * @param nextPrices 当前有效的价格修正
     */
    private WorldState withTimeline(long nextDay, List<TimedEvent> nextDeadlines, List<WorldEvent> nextEvents,
                                    Map<PriceModifier.Category, PriceModifier> nextPrices) {
        return new WorldState(worldId, contentVersion, revision, setting, settingDescription, location, currentRegion,
                mainQuest, nextDay, time, flags, originScene, nextDeadlines, nextEvents, nextPrices, visitedLocations);
    }
}
