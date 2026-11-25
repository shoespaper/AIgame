package com.gameneko.aigame.persistence;

/** 表示本地游戏数据无效或不可用，并携带可安全记录的资源与操作阶段。 */
public class GamePersistenceException extends RuntimeException {
    private final String resourceType;
    private final String resourceId;
    private final String operation;
    private final String stage;

    /** 创建存储异常。
     * @param message 返回给调用者的错误说明
     */
    public GamePersistenceException(String message) {
        this(message, null, null, null, null, null);
    }

    /** 创建带结构化诊断信息的存储异常。
     * @param message 返回给调用者的错误说明
     * @param cause 底层异常，可为空
     * @param resourceType 资源类别，可为空
     * @param resourceId 经校验的资源标识，可为空
     * @param operation 读、写等操作标识，可为空
     * @param stage 操作失败阶段，可为空
     */
    public GamePersistenceException(String message, Throwable cause, String resourceType,
                                    String resourceId, String operation, String stage) {
        super(message, cause);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.operation = operation;
        this.stage = stage;
    }

    /** 返回可空的资源类别。 */
    public String resourceType() {
        return resourceType;
    }

    /** 返回可空的资源标识。 */
    public String resourceId() {
        return resourceId;
    }

    /** 返回可空的存储操作标识。 */
    public String operation() {
        return operation;
    }

    /** 返回可空的失败阶段。 */
    public String stage() {
        return stage;
    }
}
