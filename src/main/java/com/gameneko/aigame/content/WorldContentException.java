package com.gameneko.aigame.content;

/** 区分无效的内容选择与服务器已有内容不可用。 */
public class WorldContentException extends RuntimeException {
    private final boolean unavailable;

    /** 创建对未知或非法内容标识的请求错误。
     * @param message 用户可读的内容选择错误
     */
    public WorldContentException(String message) {
        this(message, null, false);
    }

    /** 创建已配置内容的读取或解析错误。
     * @param message 内容错误说明
     * @param cause 可空的底层异常
     */
    public static WorldContentException unavailable(String message, Throwable cause) {
        return new WorldContentException(message, cause, true);
    }

    /** 创建具有明确来源的内容错误。
     * @param message 内容错误说明
     * @param cause 可空的底层异常
     * @param unavailable 是否为服务器已有内容不可用
     */
    private WorldContentException(String message, Throwable cause, boolean unavailable) {
        super(message, cause);
        this.unavailable = unavailable;
    }

    /** 返回是否属于服务器内容不可用。 */
    public boolean unavailable() {
        return unavailable;
    }
}
