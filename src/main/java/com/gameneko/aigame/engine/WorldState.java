package com.gameneko.aigame.engine;

import java.util.List;
import java.util.Map;

/** 保存引擎中立的权威世界事实、事件和按访问顺序去重的地点记录。
 * @param worldId 世界书稳定标识
 * @param contentVersion 世界内容版本
 * @param revision 目标世界修订号
 * @param setting 世界展示名称，可空
 * @param settingDescription 世界描述，可空
 * @param location 具体地点自由文本，可空
 * @param currentRegion 合法区域的稳定 id，可空
 * @param mainQuest 主线方向，可空
 * @param day 当前世界天数，以天为单位
 * @param time 当前时间描述，可空
 * @param season 当前季节描述，可空
 * @param weather 当前天气描述，可空
 * @param flags 世界布尔标记映射
 * @param threatLevel 当前威胁级别标识
 * @param threatCounter 威胁计数，单位为天
 * @param threatInterval 威胁升级间隔，单位为天
 * @param threatHistory 威胁变化历史
 * @param timedEvents 兼容的期限事件描述列表
 * @param incidentalEvents 兼容的偶发事件描述列表
 * @param lastTickDay 最后推进的世界天数
 * @param crisisTriggered 是否已触发危机
 * @param mainPlotStage 当前主线阶段索引
 * @param mainPlotStages 主线阶段描述列表
 * @param priceModifiers 兼容的价格百分比映射
 * @param originScene 开场场景描述
 * @param visitedLocations 去重且保持访问顺序的地点列表，可空时按空列表处理
 * @param deadlineEvents 类型化期限事件列表，可空
 * @param worldEvents 历史世界事件列表，可空
 * @param activePriceModifiers 有效价格百分比映射，可空
 * @param activeEffects 世界持续效果，可空
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
        String season,
        String weather,
        Map<String, Boolean> flags,
        String threatLevel,
        long threatCounter,
        long threatInterval,
        List<String> threatHistory,
        List<String> timedEvents,
        List<String> incidentalEvents,
        long lastTickDay,
        boolean crisisTriggered,
        int mainPlotStage,
        List<String> mainPlotStages,
        Map<String, Integer> priceModifiers,
        String originScene,
        List<TimedEvent> deadlineEvents,
        List<WorldEvent> worldEvents,
        Map<PriceModifier.Category, PriceModifier> activePriceModifiers,
        WorldEffects activeEffects,
        List<String> visitedLocations
) implements GameState {
    /** 复制集合并为空缺的事件、效果与地点记录提供默认值。 */
    public WorldState {
        visitedLocations = visitedLocations == null ? List.of() : List.copyOf(new java.util.LinkedHashSet<>(visitedLocations));
        flags = Map.copyOf(flags);
        threatHistory = List.copyOf(threatHistory);
        timedEvents = List.copyOf(timedEvents);
        incidentalEvents = List.copyOf(incidentalEvents);
        mainPlotStages = List.copyOf(mainPlotStages);
        priceModifiers = Map.copyOf(priceModifiers);
        deadlineEvents = deadlineEvents == null ? List.of() : List.copyOf(deadlineEvents);
        worldEvents = worldEvents == null ? List.of() : List.copyOf(worldEvents);
        activePriceModifiers = activePriceModifiers == null ? Map.of() : Map.copyOf(activePriceModifiers);
        activeEffects = activeEffects == null ? WorldEffects.none() : activeEffects;
    }

    /** 创建带访问记录的世界初始快照。
     * @param worldId 世界书稳定标识
     * @param contentVersion 世界内容版本
     * @param revision 目标世界修订号
     * @param setting 世界展示名称，可空
     * @param settingDescription 世界描述，可空
     * @param location 具体地点自由文本，可空
     * @param currentRegion 合法区域的稳定 id，可空
     * @param mainQuest 主线方向，可空
     * @param day 当前世界天数，以天为单位
     * @param time 当前时间描述，可空
     * @param season 当前季节描述，可空
     * @param weather 当前天气描述，可空
     * @param flags 世界布尔标记映射
     * @param threatLevel 当前威胁级别标识
     * @param threatCounter 威胁计数，单位为天
     * @param threatInterval 威胁升级间隔，单位为天
     * @param threatHistory 威胁变化历史
     * @param timedEvents 兼容的期限事件描述列表
     * @param incidentalEvents 兼容的偶发事件描述列表
     * @param lastTickDay 最后推进的世界天数
     * @param crisisTriggered 是否已触发危机
     * @param mainPlotStage 当前主线阶段索引
     * @param mainPlotStages 主线阶段描述列表
     * @param priceModifiers 兼容的价格百分比映射
     * @param originScene 开场场景描述
     * @param visitedLocations 去重且保持访问顺序的地点列表，可空时按空列表处理
     */
    public WorldState(String worldId, String contentVersion, long revision, String setting,
                      String settingDescription, String location, String currentRegion, String mainQuest,
                      long day, String time, String season, String weather, Map<String, Boolean> flags,
                      String threatLevel, long threatCounter, long threatInterval, List<String> threatHistory,
                      List<String> timedEvents, List<String> incidentalEvents, long lastTickDay,
                      boolean crisisTriggered, int mainPlotStage, List<String> mainPlotStages,
                      Map<String, Integer> priceModifiers, String originScene, List<String> visitedLocations) {
        this(worldId, contentVersion, revision, setting, settingDescription, location, currentRegion, mainQuest,
                day, time, season, weather, flags, threatLevel, threatCounter, threatInterval, threatHistory,
                timedEvents, incidentalEvents, lastTickDay, crisisTriggered, mainPlotStage, mainPlotStages,
                priceModifiers, originScene, List.of(), List.of(), Map.of(), WorldEffects.none(), visitedLocations);
    }

    /** 纯合并已由应用层校验的世界声明，不推进修订号；地点自动去重新增到访问记录。
     * @param updates 已校验的类型化世界字段，region 为稳定区域标识，visitedLocations 的 text 值为一个地点
     * @return 合并后的世界快照
     */
    public WorldState applyWorldUpdates(Map<String, InitializationValue> updates) {
        String nextLocation = updatedText(updates, "location", location);
        var visited = new java.util.LinkedHashSet<>(visitedLocations);
        if (updates.get("location") instanceof InitializationValue.Text text && !text.value().isBlank()) visited.add(text.value());
        if (updates.get("visitedLocations") instanceof InitializationValue.Text text && !text.value().isBlank()) visited.add(text.value());
        return new WorldState(worldId, contentVersion, revision, updatedText(updates, "name", setting),
                updatedText(updates, "description", settingDescription), nextLocation,
                updatedText(updates, "region", currentRegion), updatedText(updates, "mainQuest", mainQuest),
                day, updatedText(updates, "time", time), updatedText(updates, "season", season),
                updatedText(updates, "weather", weather), flags, threatLevel, threatCounter, threatInterval,
                threatHistory, timedEvents, incidentalEvents, lastTickDay, crisisTriggered, mainPlotStage,
                mainPlotStages, priceModifiers, updatedText(updates, "originScene", originScene),
                deadlineEvents, worldEvents, activePriceModifiers, activeEffects, List.copyOf(visited));
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
        var next = new java.util.ArrayList<>(deadlineEvents);
        if (next.stream().anyMatch(value -> value.id().equals(event.id()))) throw new IllegalArgumentException("Timed event already exists");
        if (event.status() != TimedEvent.Status.ACTIVE || event.registeredDay() > day) throw new IllegalArgumentException("Timed event must be active and registered no later than today");
        next.add(event);
        return withTyped(next, worldEvents, activePriceModifiers, activeEffects);
    }

    /** 更新修订号并保留其余世界事实和访问记录。
     * @param nextRevision 下一权威修订号
     */
    public WorldState withRevision(long nextRevision) {
        return new WorldState(worldId, contentVersion, nextRevision, setting, settingDescription, location,
                currentRegion, mainQuest, day, time, season, weather, flags, threatLevel, threatCounter,
                threatInterval, threatHistory, timedEvents, incidentalEvents, lastTickDay, crisisTriggered,
                mainPlotStage, mainPlotStages, priceModifiers, originScene, deadlineEvents, worldEvents,
                activePriceModifiers, activeEffects, visitedLocations);
    }

    /** 解决指定有效事件并记录结果。
     * @param eventId 待解决事件的唯一标识
     * @param outcome 事件解决结果
     */
    public WorldState resolveTimedEvent(String eventId, String outcome) {
        if (deadlineEvents.stream().noneMatch(event -> event.id().equals(eventId) && event.status() == TimedEvent.Status.ACTIVE)) throw new IllegalArgumentException("Active timed event not found");
        var next = deadlineEvents.stream().map(event -> event.id().equals(eventId) ? event.resolve(day, outcome) : event).toList();
        return withTyped(next, worldEvents, activePriceModifiers, activeEffects);
    }

    /** 依次应用全局和分类价格百分比计算最终价格。
     * @param basePrice 非负基础价格，单位与调用方货币一致
     * @param category 商品价格分类
     */
    public long price(long basePrice, PriceModifier.Category category) {
        if (basePrice < 0) throw new IllegalArgumentException("Base price must not be negative");
        java.math.BigInteger percent = java.math.BigInteger.valueOf(100);
        PriceModifier global = activePriceModifiers.get(PriceModifier.Category.ALL);
        if (global != null) percent = percent.multiply(java.math.BigInteger.valueOf(global.percent())).divide(java.math.BigInteger.valueOf(100));
        PriceModifier specific = activePriceModifiers.get(category);
        if (specific != null) percent = percent.multiply(java.math.BigInteger.valueOf(specific.percent())).divide(java.math.BigInteger.valueOf(100));
        return java.math.BigInteger.valueOf(basePrice).multiply(percent).divide(java.math.BigInteger.valueOf(100)).longValueExact();
    }

    /** 替换类型化事件与效果并保留世界标量和访问记录。
     * @param nextDeadlines 新期限事件列表
     * @param nextEvents 新世界事件列表
     * @param nextPrices 新有效价格修正映射
     * @param effects 新的世界持续效果
     */
    private WorldState withTyped(List<TimedEvent> nextDeadlines, List<WorldEvent> nextEvents,
                                 Map<PriceModifier.Category, PriceModifier> nextPrices,
                                 WorldEffects effects) {
        return new WorldState(worldId, contentVersion, revision, setting, settingDescription, location, currentRegion,
                mainQuest, day, time, season, weather, flags, threatLevel, threatCounter, threatInterval,
                threatHistory, timedEvents, incidentalEvents, lastTickDay, crisisTriggered, mainPlotStage,
                mainPlotStages, priceModifiers, originScene, nextDeadlines, nextEvents, nextPrices, effects, visitedLocations);
    }

}
