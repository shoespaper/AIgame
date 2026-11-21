package com.gameneko.aigame.narrative;

/** 叙事模型调用或响应解析失败时抛出的明确异常，携带用户可读信息与是否可重试。 */
public final class NarrativeProviderException extends RuntimeException {
    private final boolean retryable;

    /**
     * 创建异常。
     * @param message 用户可读的错误信息
     * @param retryable 是否属于可重试的瞬时错误
     */
    public NarrativeProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    /**
     * 创建带原因的异常。
     * @param message 用户可读的错误信息
     * @param retryable 是否属于可重试的瞬时错误
     * @param cause 底层异常
     */
    public NarrativeProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * 返回是否属于可重试的瞬时错误。
     * @return 瞬时错误返回 true
     */
    public boolean retryable() {
        return retryable;
    }
}
