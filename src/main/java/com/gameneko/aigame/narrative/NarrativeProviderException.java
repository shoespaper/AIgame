package com.gameneko.aigame.narrative;

/** 表示叙事模型调用或解析失败，携带可安全记录的原因码和上游状态。 */
public final class NarrativeProviderException extends RuntimeException {
    private final boolean retryable;
    private final String reasonCode;
    private final Integer upstreamStatus;

    /** 创建没有上游诊断字段的叙事异常。
     * @param message 用户可读的错误信息
     * @param retryable 是否属于可重试的瞬时错误
     */
    public NarrativeProviderException(String message, boolean retryable) {
        this(message, retryable, null, "provider_failed", null);
    }

    /** 创建带原因和诊断字段的叙事异常。
     * @param message 用户可读的错误信息
     * @param retryable 是否属于可重试的瞬时错误
     * @param cause 底层异常，可为空
     * @param reasonCode 固定的叙事失败原因码
     * @param upstreamStatus 上游 HTTP 状态码，无响应时为空
     */
    NarrativeProviderException(String message, boolean retryable, Throwable cause,
                               String reasonCode, Integer upstreamStatus) {
        super(message, cause);
        this.retryable = retryable;
        this.reasonCode = reasonCode;
        this.upstreamStatus = upstreamStatus;
    }

    /** 创建通过传输但无法用于业务的模型输出错误。
     * @param message 用户可读的校验提示
     * @param cause 可空的底层校验异常
     */
    public static NarrativeProviderException invalidOutput(String message, Throwable cause) {
        return new NarrativeProviderException(message, false, cause, "invalid_model_output", null);
    }

    /** 返回该错误是否允许重试。 */
    public boolean retryable() {
        return retryable;
    }

    /** 返回可写入日志的稳定原因码。 */
    public String reasonCode() {
        return reasonCode;
    }

    /** 返回上游 HTTP 状态码，无响应时为空。 */
    public Integer upstreamStatus() {
        return upstreamStatus;
    }
}
