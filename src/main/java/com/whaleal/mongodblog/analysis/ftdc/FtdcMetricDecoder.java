package com.whaleal.mongodblog.analysis.ftdc;

import com.whaleal.mongodblog.parser.ftdc.FtdcBlockScanner;
import com.whaleal.mongodblog.parser.ftdc.FtdcFileReader;
import com.whaleal.mongodblog.parser.ftdc.FtdcFormatException;
import com.whaleal.mongodblog.parser.ftdc.FtdcVarIntReader;
import com.whaleal.mongodblog.storage.ftdc.FtdcIndexReader;
import com.whaleal.mongodblog.storage.ftdc.FtdcIndexWriter;
import org.bson.RawBsonDocument;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class FtdcMetricDecoder {
    private final FtdcBlockScanner scanner = new FtdcBlockScanner();

    public SourceChannels openSources(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files) {
        return new SourceChannels(sourceDirectory, files);
    }

    public DecodedBlock decode(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                               FtdcIndexReader.MetricBlockIndex block) throws IOException {
        try (SourceChannels sources = openSources(sourceDirectory, files)) {
            return decode(sources, block);
        }
    }

    public DecodedBlock decode(SourceChannels sources, FtdcIndexReader.MetricBlockIndex block) throws IOException {
        FtdcBlockScanner.validateScale(1, block.numDeltas(), location(block.fileOffset(), block.blockOrdinal()));
        byte[] payload = readPayload(sources, block.fileId(), block.blockOrdinal(), block.fileOffset(),
                block.documentLength(), block.compressedLength(), block.declaredLength());
        ColumnDecoder times = new ColumnDecoder(payload, block.numDeltas(), block.timeBaseline(),
                block.timeDeltaOffset(), block.timeZerosAtStart());
        ColumnDecoder values = block.metricDeltaOffset() == block.timeDeltaOffset()
                ? times
                : new ColumnDecoder(payload, block.numDeltas(), block.metricBaseline(),
                block.metricDeltaOffset(), block.metricZerosAtStart());
        return new DecodedBlock(block.numDeltas() + 1, times, values);
    }

    public GroupDecodedBlock decodeGroup(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files,
                                         FtdcIndexReader.GroupMetricBlockIndex block) throws IOException {
        try (SourceChannels sources = openSources(sourceDirectory, files)) {
            return decodeGroup(sources, block);
        }
    }

    public GroupDecodedBlock decodeGroup(SourceChannels sources,
                                         FtdcIndexReader.GroupMetricBlockIndex block) throws IOException {
        FtdcBlockScanner.validateScale(block.numAttributes(), block.numDeltas(),
                location(block.fileOffset(), block.blockOrdinal()));
        byte[] payload = readPayload(sources, block.fileId(), block.blockOrdinal(), block.fileOffset(),
                block.documentLength(), block.compressedLength(), block.declaredLength());
        ColumnDecoder times = new ColumnDecoder(payload, block.numDeltas(), block.timeBaseline(),
                block.timeDeltaOffset(), block.timeZerosAtStart());
        ColumnDecoder[] values = new ColumnDecoder[block.metricIndexes().length];
        for (int i = 0; i < values.length; i++) {
            values[i] = block.metricDeltaOffsets()[i] == block.timeDeltaOffset()
                    ? times
                    : new ColumnDecoder(payload, block.numDeltas(), block.metricBaselines()[i],
                    block.metricDeltaOffsets()[i], block.metricZerosAtStart()[i]);
        }
        return new GroupDecodedBlock(block.numDeltas() + 1, block.metricIndexes(), times, values);
    }

    private byte[] readPayload(SourceChannels sources, int fileId, int blockOrdinal, long fileOffset,
                               int documentLength, int compressedLength, int declaredLength) throws IOException {
        String location = location(fileOffset, blockOrdinal);
        if (fileOffset < 0 || documentLength < 5 || documentLength > FtdcFileReader.MAX_DOCUMENT_BYTES) {
            throw new FtdcFormatException(location + "源 block 文档长度非法：" + documentLength);
        }
        if (compressedLength < 8 || declaredLength < 5
                || declaredLength > FtdcBlockScanner.MAX_UNCOMPRESSED_BLOCK_BYTES) {
            throw new FtdcFormatException(location + "源 block 长度索引非法");
        }
        byte[] document = new byte[documentLength];
        FileChannel channel = sources.channel(fileId);
        ByteBuffer target = ByteBuffer.wrap(document);
        while (target.hasRemaining()) {
            int read = channel.read(target, fileOffset + target.position());
            if (read < 0) throw new EOFException("FTDC 源 block 截断");
        }
        byte[] data;
        try {
            data = new RawBsonDocument(document).getBinary("data").getData();
        } catch (RuntimeException e) {
            throw new FtdcFormatException(location + "源 block BSON 无法解析", e);
        }
        if (data.length - 4 != compressedLength) {
            throw new FtdcFormatException(location + "源 block 压缩长度与索引不一致");
        }
        byte[] payload = scanner.decompress(data, location);
        if (payload.length != declaredLength) {
            throw new FtdcFormatException(location + "源 block 解压长度与索引不一致");
        }
        return payload;
    }

    private static String location(long fileOffset, int blockOrdinal) {
        return "FTDC 偏移 " + fileOffset + "，block " + blockOrdinal + "：";
    }

    private static final class ColumnDecoder {
        private final byte[] payload;
        private final int numDeltas;
        private final FtdcVarIntReader reader = new FtdcVarIntReader();
        private final FtdcVarIntReader.Cursor cursor;
        private long current;
        private long zerosLeft;
        private int point = -1;

        private ColumnDecoder(byte[] payload, int numDeltas, long baseline, int offset, int initialZeros) {
            if (offset < 0 || offset > payload.length || initialZeros < 0) {
                throw new FtdcFormatException("FTDC 指标索引位置或零游程非法");
            }
            this.payload = payload;
            this.numDeltas = numDeltas;
            this.cursor = new FtdcVarIntReader.Cursor(offset);
            this.current = baseline;
            this.zerosLeft = initialZeros;
        }

        private long next() {
            if (point < 0) {
                point = 0;
                return current;
            }
            if (point >= numDeltas) throw new IllegalStateException("FTDC 指标列已读取完成");
            long delta;
            if (zerosLeft > 0) {
                delta = 0;
                zerosLeft--;
            } else {
                delta = reader.read(payload, cursor);
                if (delta == 0) {
                    zerosLeft = reader.read(payload, cursor);
                }
            }
            current += delta;
            point++;
            return current;
        }
    }

    public static final class DecodedBlock {
        private final int pointCount;
        private final ColumnDecoder timestamps;
        private final ColumnDecoder values;
        private int position;
        private long timestamp;
        private long value;

        private DecodedBlock(int pointCount, ColumnDecoder timestamps, ColumnDecoder values) {
            this.pointCount = pointCount;
            this.timestamps = timestamps;
            this.values = values;
        }

        public boolean advance() {
            if (position >= pointCount) return false;
            timestamp = timestamps.next();
            value = values == timestamps ? timestamp : values.next();
            position++;
            return true;
        }

        public long timestamp() {
            return timestamp;
        }

        public long value() {
            return value;
        }
    }

    public static final class GroupDecodedBlock {
        private final int pointCount;
        private final int[] metricIndexes;
        private final ColumnDecoder timestamps;
        private final ColumnDecoder[] values;
        private final long[] currentValues;
        private int position;
        private long timestamp;

        private GroupDecodedBlock(int pointCount, int[] metricIndexes, ColumnDecoder timestamps,
                                  ColumnDecoder[] values) {
            this.pointCount = pointCount;
            this.metricIndexes = metricIndexes.clone();
            this.timestamps = timestamps;
            this.values = values;
            this.currentValues = new long[values.length];
        }

        public boolean advance() {
            if (position >= pointCount) return false;
            timestamp = timestamps.next();
            for (int i = 0; i < values.length; i++) {
                currentValues[i] = values[i] == timestamps ? timestamp : values[i].next();
            }
            position++;
            return true;
        }

        public long timestamp() {
            return timestamp;
        }

        public int[] metricIndexes() {
            return metricIndexes;
        }

        public long value(int column) {
            return currentValues[column];
        }
    }

    public static final class SourceChannels implements AutoCloseable {
        private final Path sourceDirectory;
        private final List<FtdcIndexWriter.SourceFile> files;
        private final FileChannel[] channels;

        private SourceChannels(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files) {
            this.sourceDirectory = sourceDirectory.toAbsolutePath().normalize();
            this.files = List.copyOf(files);
            this.channels = new FileChannel[files.size()];
        }

        private FileChannel channel(int fileId) throws IOException {
            if (fileId < 0 || fileId >= files.size()) {
                throw new FtdcFormatException("FTDC 索引引用了不存在的源文件");
            }
            if (channels[fileId] == null) {
                Path source = sourceDirectory.resolve(files.get(fileId).storedName()).normalize();
                if (!source.startsWith(sourceDirectory)) {
                    throw new FtdcFormatException("FTDC 源文件路径越界");
                }
                channels[fileId] = FileChannel.open(source, StandardOpenOption.READ);
            }
            return channels[fileId];
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (FileChannel channel : channels) {
                if (channel == null) continue;
                try {
                    channel.close();
                } catch (IOException e) {
                    if (failure == null) failure = e;
                    else failure.addSuppressed(e);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
