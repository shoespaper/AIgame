package com.gameneko.aigame.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 清理写入失败后遗留的临时文件，并保留清理异常。 */
final class TemporaryFiles {
    /** 工具类无需实例。 */
    private TemporaryFiles() {
    }

    /** 在原始写入失败后清理临时文件，清理失败时附加到原始异常。
     * @param path 已创建的临时文件路径，创建失败时为空
     * @param failure 原始操作异常
     */
    static void deleteAfterFailure(Path path, Throwable failure) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
