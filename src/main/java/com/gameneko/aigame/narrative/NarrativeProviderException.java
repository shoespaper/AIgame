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

    /** 判断再次请求是否可能修复本次传输失败或模型输出错误。 */
    public boolean canRegenerate() {
        return retryable || switch (reasonCode) {
            case "empty_output", "output_parse_failed", "invalid_model_output", "response_decoding_failed",
                    "missing_choice", "output_truncated", "incomplete_output" -> true;
            default -> false;
        };
    }

    /** 给下一次模型调用提供归一化的失败原因。 */
    public String regenerationHint() {
        return switch (reasonCode) {
            case "empty_output" -> "上次回复只有空白。";
            case "output_truncated", "incomplete_output" -> "上次回复未完整结束。请缩短内容并完整闭合 JSON。";
            case "output_parse_failed", "invalid_model_output", "response_decoding_failed", "missing_choice" ->
                    "上次回复不符合输出协议。";
            default -> "上次未取得有效的模型回复。";
        };
    }

    /** 在瞬时传输错误后短暂退避。校验失败可立即修正。
     * @param attempt 已失败的尝试序号，从 1 开始
     * @param limit 本轮允许的最大尝试次数
     */
    public void pauseBeforeRetry(int attempt, int limit) {
        if (!retryable || attempt >= limit) return;
        try {
            Thread.sleep(250L << Math.min(attempt - 1, 3));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Model retry was interrupted", exception);
        }
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
