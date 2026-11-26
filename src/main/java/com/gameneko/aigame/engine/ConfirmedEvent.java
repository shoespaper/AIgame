package com.gameneko.aigame.engine;

/** 记录供叙事使用、由服务端确认的引擎事实。
 * @param source 事件所属引擎标识
 * @param type 引擎定义的事件类型
 * @param description 已确认结果的文字说明
 * @param amount 事件的主要数值，单位由事件类型确定
 * @param subject 行动者的可空标识
 * @param target 受影响实体的可空标识
 */
public record ConfirmedEvent(String source, String type, String description, long amount, String subject, String target) {
    /** 创建不涉及具体实体的确认事实。
     * @param source 事件所属引擎标识
     * @param type 引擎定义的事件类型
     * @param description 已确认结果的文字说明
     * @param amount 事件的主要数值
     */
    public ConfirmedEvent(String source, String type, String description, long amount) {
        this(source, type, description, amount, "", "");
    }

    /** 创建没有数值的确认事实。
     * @param source 事件所属引擎标识
     * @param type 引擎定义的事件类型
     * @param description 已确认结果的文字说明
     */
    public ConfirmedEvent(String source, String type, String description) {
        this(source, type, description, 0);
    }

    public ConfirmedEvent {
        if (source == null || source.isBlank() || type == null || type.isBlank()) throw new IllegalArgumentException("Event source and type are required");
        source = source.trim();
        type = type.trim();
        description = description == null ? "" : description;
        subject = subject == null ? "" : subject;
        target = target == null ? "" : target;
    }
}
