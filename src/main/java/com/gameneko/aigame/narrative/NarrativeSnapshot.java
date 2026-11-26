package com.gameneko.aigame.narrative;

import com.gameneko.aigame.content.WorldbookDefinition;

import java.util.List;

/** 将动态世界事实、确认事件、引擎投影及世界书设定作为只读数据传给叙事模型。 */
public record NarrativeSnapshot(WorldView world,
                                List<ConfirmedEvent> confirmedEvents, Object engineProjection,
                                WorldbookDefinition worldbook, Object templateMaterials) {
    /** 创建不附带世界书资料的快照。
     * @param world 世界动态视图
     * @param confirmedEvents 已确认事件
     * @param engineProjection 引擎中立的展示投影
     */
    public NarrativeSnapshot(WorldView world, List<ConfirmedEvent> confirmedEvents, Object engineProjection) {
        this(world, confirmedEvents, engineProjection, null, null);
    }
    /** 创建不附带引擎和世界书资料的快照。
     * @param world 世界动态视图
     * @param confirmedEvents 已确认事件
     */
    public NarrativeSnapshot(WorldView world, List<ConfirmedEvent> confirmedEvents) {
        this(world, confirmedEvents, null);
    }
    /** 校验叙事快照并复制确认事件。
     * @param world 世界动态视图
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
    public NarrativeSnapshot withWorldbook(WorldbookDefinition setting) {
        return new NarrativeSnapshot(world, confirmedEvents, engineProjection, setting, templateMaterials);
    }

    /** 补充确认阶段登记的世界创作素材。
     * @param materials 世界类型对应的叙事素材
     */
    public NarrativeSnapshot withMaterials(Object materials) {
        return new NarrativeSnapshot(world, confirmedEvents, engineProjection, worldbook, materials);
    }

    public record WorldView(long day, String time,
                            String location, String region,
                            List<TimedEventView> deadlines, List<WorldEventView> recentEvents,
                            List<PriceView> prices) {
        public WorldView {
            deadlines = deadlines == null ? List.of() : List.copyOf(deadlines);
            recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
            prices = prices == null ? List.of() : List.copyOf(prices);
        }
    }

    public record TimedEventView(String id, String description, long daysRemaining, String status) {}
    public record WorldEventView(long day, String type, String description, String materialId) {}
    public record PriceView(String category, int multiplierPercent) {}
    public record ConfirmedEvent(String domain, String type, String subject,
                                 String target, long amount, String outcome) {}
}
