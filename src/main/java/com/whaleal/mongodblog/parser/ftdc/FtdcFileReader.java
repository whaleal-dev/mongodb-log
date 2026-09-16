package com.whaleal.mongodblog.parser.ftdc;

import org.bson.BsonValue;
import org.bson.RawBsonDocument;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FtdcFileReader {
    public static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    public ScanSummary scan(Path path, DocumentConsumer consumer) throws IOException {
        int documents = 0;
        int metricBlocks = 0;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long offset = 0;
            while (offset < channel.size()) {
                int length = readLength(channel, offset);
                if (length < 5 || length > MAX_DOCUMENT_BYTES) {
                    throw error(offset, "BSON 长度非法：" + length, null);
                }
                if (channel.size() - offset < length) {
                    throw error(offset, "BSON 文档截断，声明 " + length + " 字节", null);
                }
                byte[] bytes = new byte[length];
                ByteBuffer documentBuffer = ByteBuffer.wrap(bytes);
                readFully(channel, documentBuffer, offset);
                RawBsonDocument document;
                try {
                    document = new RawBsonDocument(bytes);
                    int type = numericType(document.get("type"), offset);
                    byte[] data = type == 1 ? binaryData(document.get("data"), offset) : null;
                    consumer.accept(new FtdcDocument(offset, length, type, data));
                    if (type == 1) {
                        metricBlocks++;
                    }
                } catch (FtdcFormatException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw error(offset, "BSON 文档无法解析", e);
                }
                documents++;
                offset += length;
            }
        }
        if (metricBlocks == 0) {
            throw new FtdcFormatException("FTDC 文件不包含合法的 type=1 指标 block：" + path.getFileName());
        }
        return new ScanSummary(documents, metricBlocks);
    }

    private int readLength(FileChannel channel, long offset) throws IOException {
        ByteBuffer length = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        while (length.hasRemaining()) {
            int read = channel.read(length, offset + length.position());
            if (read < 0) {
                throw error(offset, "BSON 长度字段截断", new EOFException());
            }
        }
        length.flip();
        return length.getInt();
    }

    private void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, offset + target.position());
            if (read < 0) {
                throw new EOFException("BSON 文档截断");
            }
        }
    }

    private int numericType(BsonValue value, long offset) {
        if (value == null || !value.isNumber()) {
            throw error(offset, "缺少数值 type 字段", null);
        }
        return value.asNumber().intValue();
    }

    private byte[] binaryData(BsonValue value, long offset) {
        if (value == null || !value.isBinary()) {
            throw error(offset, "type=1 block 缺少二进制 data 字段", null);
        }
        return value.asBinary().getData();
    }

    private FtdcFormatException error(long offset, String message, Throwable cause) {
        String detail = "FTDC 偏移 " + offset + "：" + message;
        return cause == null ? new FtdcFormatException(detail) : new FtdcFormatException(detail, cause);
    }

    @FunctionalInterface
    public interface DocumentConsumer {
        void accept(FtdcDocument document) throws IOException;
    }

    public record ScanSummary(int documentCount, int metricsBlockCount) {
    }
}
