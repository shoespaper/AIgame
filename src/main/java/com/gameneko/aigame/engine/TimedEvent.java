package com.gameneko.aigame.engine;

/** 保存有期限的叙事事件及处理结果。
 * @param id 本次事件标识
 * @param kind 事件的叙事类别
 * @param description 事件描述
 * @param answer 可供叙事参考的解决线索
 * @param consequence 到期时确认的后果文本
 * @param registeredDay 登记当天的世界天数
 * @param deadlineDay 事件到期的世界天数
 * @param daysRemaining 剩余天数
 * @param status 当前事件状态
 * @param materialId 到期事实关联的世界素材标识，可为空字符串
 * @param outcome 解决结果，可为空字符串
 * @param resolvedDay 结案当天的世界天数，未结案时为零
 */
public record TimedEvent(String id, String kind, String description, String answer,
                         String consequence, long registeredDay, long deadlineDay,
                         long daysRemaining, Status status, String materialId,
                         String outcome, long resolvedDay) {
    public enum Status { ACTIVE, RESOLVED, EXPIRED }

    public TimedEvent {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Timed event id is required");
        kind = kind == null ? "foreshadow" : kind;
        description = description == null ? "" : description;
        answer = answer == null ? "" : answer;
        consequence = consequence == null ? "" : consequence;
        if (deadlineDay < registeredDay) throw new IllegalArgumentException("Timed event deadline is invalid");
        status = status == null ? Status.ACTIVE : status;
        materialId = materialId == null ? "" : materialId;
        outcome = outcome == null ? "" : outcome;
        if (resolvedDay < 0) throw new IllegalArgumentException("Resolved day must not be negative");
    }

    /** 创建尚未结案的期限事件。
     * @param id 本次事件标识
     * @param kind 事件的叙事类别
     * @param description 事件描述
     * @param answer 解决线索
     * @param consequence 到期时确认的后果文本
     * @param registeredDay 登记当天的世界天数
     * @param deadlineDay 事件到期的世界天数
     * @param daysRemaining 剩余天数
     * @param status 事件状态
     * @param materialId 到期事实关联的世界素材标识，可为空字符串
     */
    public TimedEvent(String id, String kind, String description, String answer, String consequence,
                      long registeredDay, long deadlineDay, long daysRemaining, Status status,
                      String materialId) {
        this(id, kind, description, answer, consequence, registeredDay, deadlineDay, daysRemaining,
                status, materialId, "", 0);
    }

    /** 根据当前日期更新期限和到期状态。
     * @param day 推进后的世界天数
     */
    public TimedEvent tick(long day) {
        if (status != Status.ACTIVE) return this;
        long remaining = Math.max(0, deadlineDay - day);
        return remaining == 0
                ? new TimedEvent(id, kind, description, answer, consequence, registeredDay,
                deadlineDay, 0, Status.EXPIRED, materialId, outcome, day)
                : new TimedEvent(id, kind, description, answer, consequence, registeredDay,
                deadlineDay, remaining, status, materialId, outcome, resolvedDay);
    }

    /** 保存事件的解决结果并停止倒计时。
     * @param day 结案当天的世界天数
     * @param outcome GM 确认的解决结果
     */
    public TimedEvent resolve(long day, String outcome) {
        if (status != Status.ACTIVE) throw new IllegalArgumentException("Timed event is not active");
        return new TimedEvent(id, kind, description, answer, consequence,
                registeredDay, deadlineDay, Math.max(0, deadlineDay - day), Status.RESOLVED,
                materialId, outcome, day);
    }
}
