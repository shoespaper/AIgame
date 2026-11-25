package com.gameneko.aigame.logging;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 从当前日志文件尾部读取固定数量的完整 JSON 行，保留写入顺序及原始 UTF-8 字节。 */
public final class LogTailReader {
    private static final int RECORD_LIMIT = 300;
    private static final int BLOCK_SIZE = 8192;

    /** 工具类无需实例。 */
    private LogTailReader() {
    }

    /** 获取打开文件时可见的最近 300 条完整日志，排除尚未换行的末尾记录。
     * @param path 配置中指定的当前日志文件路径
     */
    public static byte[] read(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long position = channel.size();
            long completeEnd = -1;
            int records = 0;
            ByteBuffer block = ByteBuffer.allocate(BLOCK_SIZE);

            while (position > 0) {
                int size = (int) Math.min(position, BLOCK_SIZE);
                position -= size;
                block.clear().limit(size);
                readFully(channel, block, position);

                for (int index = size - 1; index >= 0; index--) {
                    if (block.get(index) != '\n') continue;
                    long limit = position + index + 1;
                    if (completeEnd < 0) completeEnd = limit;
                    else if (++records == RECORD_LIMIT) return readRange(channel, limit, completeEnd);
                }
            }
            return completeEnd < 0 ? new byte[0] : readRange(channel, 0, completeEnd);
        } catch (NoSuchFileException exception) {
            return new byte[0];
        }
    }

    /** 按文件偏移完整读取选中的字节区间。
     * @param channel 已打开的当前日志通道
     * @param start 起始字节偏移，包含该位置
     * @param end 结束字节偏移，不包含该位置
     */
    private static byte[] readRange(FileChannel channel, long start, long end) throws IOException {
        ByteBuffer result = ByteBuffer.allocate(Math.toIntExact(end - start));
        readFully(channel, result, start);
        return result.array();
    }

    /** 读取指定区间，文件在读取中被截断时报告失败。
     * @param channel 已打开的当前日志通道
     * @param buffer 目标缓冲区，以剩余容量确定读取字节数
     * @param position 起始字节偏移
     */
    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) throw new EOFException("Log file changed during export");
            position += read;
        }
    }
}
