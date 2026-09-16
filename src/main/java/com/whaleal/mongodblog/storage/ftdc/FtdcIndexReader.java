package com.whaleal.mongodblog.storage.ftdc;

import com.whaleal.mongodblog.parser.ftdc.FtdcBlockScanner;
import com.whaleal.mongodblog.parser.ftdc.FtdcSchema;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FtdcIndexReader {
    private final Path path;

    public FtdcIndexReader(Path path) {
        this.path = path;
    }

    public List<FtdcIndexWriter.SourceFile> files() {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            Cursor cursor = readHeader(channel);
            return readFiles(channel, cursor);
        } catch (IOException | RuntimeException e) {
            throw indexError(e);
        }
    }

    public List<FtdcSchema> schemas() {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            Cursor cursor = readHeader(channel);
            readFiles(channel, cursor);
            return readSchemas(channel, cursor);
        } catch (IOException | RuntimeException e) {
            throw indexError(e);
        }
    }

    public List<FtdcBlockIndex> blocks() {
        List<FtdcBlockIndex> blocks = new ArrayList<>();
        visitBlocks(blocks::add);
        return blocks;
    }

    public void visitBlocks(BlockConsumer consumer) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            Cursor cursor = readHeader(channel);
            readFiles(channel, cursor);
            readSchemas(channel, cursor);
            for (int i = 0; i < cursor.blockCount; i++) {
                consumer.accept(readBlock(channel, cursor));
            }
            if (cursor.position != channel.size()) {
                throw new IllegalStateException("FTDC 索引包含多余数据");
            }
        } catch (IOException | RuntimeException e) {
            throw indexError(e);
        }
    }

    public List<MetricBlockIndex> metricBlocks(String metricPath) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            Cursor cursor = readHeader(channel);
            readFiles(channel, cursor);
            List<FtdcSchema> schemas = readSchemas(channel, cursor);
            List<MetricBlockIndex> result = new ArrayList<>();
            for (int blockNumber = 0; blockNumber < cursor.blockCount; blockNumber++) {
                int fileId = readInt(channel, cursor);
                int ordinal = readInt(channel, cursor);
                long fileOffset = readLong(channel, cursor);
                int documentLength = readInt(channel, cursor);
                int declaredLength = readInt(channel, cursor);
                int compressedLength = readInt(channel, cursor);
                int schemaId = readInt(channel, cursor);
                int numAttributes = checkedCount(readInt(channel, cursor), "属性");
                int numDeltas = checkedCount(readInt(channel, cursor), "delta");
                FtdcBlockScanner.validateScale(numAttributes, numDeltas, "FTDC 索引：");
                long start = readLong(channel, cursor);
                long end = readLong(channel, cursor);
                long pointOffset = readLong(channel, cursor);
                if (schemaId < 0 || schemaId >= schemas.size() || schemas.get(schemaId).paths().size() != numAttributes) {
                    throw new IllegalStateException("FTDC 索引 schema 与属性数量不一致");
                }
                int metricIndex = schemas.get(schemaId).indexOf(metricPath);
                int timeIndex = schemas.get(schemaId).indexOf("start");
                long arraysStart = cursor.position;
                long metricBaseline = metricIndex < 0 ? 0 : readLongAt(channel, arraysStart + (long) metricIndex * 8);
                long timeBaseline = timeIndex < 0 ? 0 : readLongAt(channel, arraysStart + (long) timeIndex * 8);
                long offsetsStart = arraysStart + (long) numAttributes * 8;
                int metricOffset = metricIndex < 0 ? 0 : readIntAt(channel, offsetsStart + (long) metricIndex * 4);
                int timeOffset = timeIndex < 0 ? 0 : readIntAt(channel, offsetsStart + (long) timeIndex * 4);
                long zerosStart = offsetsStart + (long) numAttributes * 4;
                int metricZeros = metricIndex < 0 ? 0 : readIntAt(channel, zerosStart + (long) metricIndex * 4);
                int timeZeros = timeIndex < 0 ? 0 : readIntAt(channel, zerosStart + (long) timeIndex * 4);
                cursor.position = zerosStart + (long) numAttributes * 4;
                if (metricIndex >= 0 && timeIndex >= 0) {
                    result.add(new MetricBlockIndex(fileId, ordinal, fileOffset, documentLength, declaredLength,
                            compressedLength, schemaId, numDeltas, start, end, pointOffset,
                            metricBaseline, metricOffset, metricZeros, timeBaseline, timeOffset, timeZeros));
                }
            }
            if (cursor.position != channel.size()) throw new IllegalStateException("FTDC 索引包含多余数据");
            return List.copyOf(result);
        } catch (IOException | RuntimeException e) {
            throw indexError(e);
        }
    }

    public List<GroupMetricBlockIndex> groupMetricBlocks(List<String> metricPaths) {
        return groupQuery(metricPaths).blocks();
    }

    public GroupQueryIndex groupQuery(List<String> metricPaths) {
        if (metricPaths.isEmpty() || metricPaths.size() > 200) {
            throw new IllegalArgumentException("FTDC 指标组必须包含 1 到 200 个指标");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            Cursor cursor = readHeader(channel);
            List<FtdcIndexWriter.SourceFile> files = readFiles(channel, cursor);
            List<FtdcSchema> schemas = readSchemas(channel, cursor);
            List<GroupMetricBlockIndex> result = new ArrayList<>();
            for (int blockNumber = 0; blockNumber < cursor.blockCount; blockNumber++) {
                GroupMetricBlockIndex block = readGroupBlock(channel, cursor, schemas, metricPaths);
                if (block != null) result.add(block);
            }
            if (cursor.position != channel.size()) throw new IllegalStateException("FTDC 索引包含多余数据");
            return new GroupQueryIndex(files, schemas, result);
        } catch (IOException | RuntimeException e) {
            throw indexError(e);
        }
    }

    public void validateSources(Path sourceDirectory) {
        validateSources(sourceDirectory, files());
    }

    public void validateSources(Path sourceDirectory, List<FtdcIndexWriter.SourceFile> files) {
        try {
            for (FtdcIndexWriter.SourceFile expected : files) {
                Path source = sourceDirectory.resolve(expected.storedName()).normalize();
                if (!source.startsWith(sourceDirectory.toAbsolutePath().normalize())) {
                    throw new IllegalStateException("FTDC 源文件路径越界");
                }
                FtdcIndexWriter.SourceFile actual = FtdcIndexWriter.SourceFile.from(
                        expected.order(), expected.storedName(), source, expected.blockCount());
                if (expected.sizeBytes() != actual.sizeBytes() || !expected.sha256().equals(actual.sha256())) {
                    throw new IllegalStateException("FTDC 源文件校验失败：" + expected.storedName());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("FTDC 源文件校验失败：" + e.getMessage(), e);
        }
    }

    private GroupMetricBlockIndex readGroupBlock(FileChannel channel, Cursor cursor, List<FtdcSchema> schemas,
                                                  List<String> metricPaths) throws IOException {
        int fileId = readInt(channel, cursor);
        int ordinal = readInt(channel, cursor);
        long fileOffset = readLong(channel, cursor);
        int documentLength = readInt(channel, cursor);
        int declaredLength = readInt(channel, cursor);
        int compressedLength = readInt(channel, cursor);
        int schemaId = readInt(channel, cursor);
        int numAttributes = checkedCount(readInt(channel, cursor), "属性");
        int numDeltas = checkedCount(readInt(channel, cursor), "delta");
        FtdcBlockScanner.validateScale(numAttributes, numDeltas, "FTDC 索引：");
        long start = readLong(channel, cursor);
        long end = readLong(channel, cursor);
        long pointOffset = readLong(channel, cursor);
        if (schemaId < 0 || schemaId >= schemas.size() || schemas.get(schemaId).paths().size() != numAttributes) {
            throw new IllegalStateException("FTDC 索引 schema 与属性数量不一致");
        }
        FtdcSchema schema = schemas.get(schemaId);
        int timeIndex = schema.indexOf("start");
        int[] schemaIndexes = new int[metricPaths.size()];
        int[] resultIndexes = new int[metricPaths.size()];
        int count = 0;
        for (int resultIndex = 0; resultIndex < metricPaths.size(); resultIndex++) {
            int schemaIndex = schema.indexOf(metricPaths.get(resultIndex));
            if (schemaIndex < 0) continue;
            schemaIndexes[count] = schemaIndex;
            resultIndexes[count] = resultIndex;
            count++;
        }
        long arraysStart = cursor.position;
        long offsetsStart = arraysStart + (long) numAttributes * Long.BYTES;
        long zerosStart = offsetsStart + (long) numAttributes * Integer.BYTES;
        cursor.position = zerosStart + (long) numAttributes * Integer.BYTES;
        if (timeIndex < 0 || count == 0) return null;

        int[] metricIndexes = Arrays.copyOf(resultIndexes, count);
        long[] baselines = new long[count];
        int[] offsets = new int[count];
        int[] zeros = new int[count];
        for (int i = 0; i < count; i++) {
            int schemaIndex = schemaIndexes[i];
            baselines[i] = readLongAt(channel, arraysStart + (long) schemaIndex * Long.BYTES);
            offsets[i] = readIntAt(channel, offsetsStart + (long) schemaIndex * Integer.BYTES);
            zeros[i] = readIntAt(channel, zerosStart + (long) schemaIndex * Integer.BYTES);
        }
        return new GroupMetricBlockIndex(fileId, ordinal, fileOffset, documentLength, declaredLength,
                compressedLength, numAttributes, numDeltas, start, end, pointOffset, metricIndexes,
                baselines, offsets, zeros, readLongAt(channel, arraysStart + (long) timeIndex * Long.BYTES),
                readIntAt(channel, offsetsStart + (long) timeIndex * Integer.BYTES),
                readIntAt(channel, zerosStart + (long) timeIndex * Integer.BYTES));
    }

    private Cursor readHeader(FileChannel channel) throws IOException {
        Cursor cursor = new Cursor();
        byte[] magic = readBytes(channel, cursor, FtdcIndexWriter.MAGIC.length);
        if (!Arrays.equals(magic, FtdcIndexWriter.MAGIC)) {
            throw new IllegalStateException("FTDC 索引 magic 不匹配");
        }
        int version = readInt(channel, cursor);
        if (version != FtdcIndexWriter.VERSION) {
            throw new IllegalStateException("FTDC 索引版本不支持：" + version);
        }
        cursor.fileCount = checkedCount(readInt(channel, cursor), "文件");
        cursor.schemaCount = checkedCount(readInt(channel, cursor), "schema");
        cursor.blockCount = checkedCount(readInt(channel, cursor), "block");
        return cursor;
    }

    private List<FtdcIndexWriter.SourceFile> readFiles(FileChannel channel, Cursor cursor) throws IOException {
        List<FtdcIndexWriter.SourceFile> files = new ArrayList<>(cursor.fileCount);
        for (int i = 0; i < cursor.fileCount; i++) {
            files.add(new FtdcIndexWriter.SourceFile(readInt(channel, cursor), readString(channel, cursor),
                    readLong(channel, cursor), readString(channel, cursor), readInt(channel, cursor)));
        }
        return List.copyOf(files);
    }

    private List<FtdcSchema> readSchemas(FileChannel channel, Cursor cursor) throws IOException {
        List<FtdcSchema> schemas = new ArrayList<>(cursor.schemaCount);
        for (int i = 0; i < cursor.schemaCount; i++) {
            int id = readInt(channel, cursor);
            if (id != i) throw new IllegalStateException("FTDC schema ID 不连续");
            int pathCount = checkedCount(readInt(channel, cursor), "指标");
            List<String> paths = new ArrayList<>(pathCount);
            for (int p = 0; p < pathCount; p++) paths.add(readString(channel, cursor));
            schemas.add(new FtdcSchema(paths));
        }
        return List.copyOf(schemas);
    }

    private FtdcBlockIndex readBlock(FileChannel channel, Cursor cursor) throws IOException {
        int fileId = readInt(channel, cursor);
        int ordinal = readInt(channel, cursor);
        long fileOffset = readLong(channel, cursor);
        int documentLength = readInt(channel, cursor);
        int declaredLength = readInt(channel, cursor);
        int compressedLength = readInt(channel, cursor);
        int schemaId = readInt(channel, cursor);
        int numAttributes = checkedCount(readInt(channel, cursor), "属性");
        int numDeltas = checkedCount(readInt(channel, cursor), "delta");
        FtdcBlockScanner.validateScale(numAttributes, numDeltas, "FTDC 索引：");
        long start = readLong(channel, cursor);
        long end = readLong(channel, cursor);
        long pointOffset = readLong(channel, cursor);
        long[] baseline = new long[numAttributes];
        int[] offsets = new int[numAttributes];
        int[] zeros = new int[numAttributes];
        for (int i = 0; i < numAttributes; i++) baseline[i] = readLong(channel, cursor);
        for (int i = 0; i < numAttributes; i++) offsets[i] = readInt(channel, cursor);
        for (int i = 0; i < numAttributes; i++) zeros[i] = readInt(channel, cursor);
        return new FtdcBlockIndex(fileId, ordinal, fileOffset, documentLength, declaredLength, compressedLength,
                schemaId, numDeltas, start, end, pointOffset, baseline, offsets, zeros);
    }

    private int checkedCount(int value, String name) {
        if (value < 0 || value > 10_000_000) throw new IllegalStateException("FTDC 索引" + name + "数量非法：" + value);
        return value;
    }

    private String readString(FileChannel channel, Cursor cursor) throws IOException {
        int length = readInt(channel, cursor);
        if (length < 0 || length > 16 * 1024 * 1024) throw new IllegalStateException("FTDC 索引字符串长度非法");
        return new String(readBytes(channel, cursor, length), StandardCharsets.UTF_8);
    }

    private int readInt(FileChannel channel, Cursor cursor) throws IOException {
        return ByteBuffer.wrap(readBytes(channel, cursor, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private long readLong(FileChannel channel, Cursor cursor) throws IOException {
        return ByteBuffer.wrap(readBytes(channel, cursor, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private int readIntAt(FileChannel channel, long position) throws IOException {
        return ByteBuffer.wrap(readBytesAt(channel, position, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private long readLongAt(FileChannel channel, long position) throws IOException {
        return ByteBuffer.wrap(readBytesAt(channel, position, 8)).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private byte[] readBytesAt(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) throw new EOFException("FTDC 索引截断");
        }
        return buffer.array();
    }

    private byte[] readBytes(FileChannel channel, Cursor cursor, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, cursor.position + buffer.position());
            if (read < 0) throw new EOFException("FTDC 索引截断");
        }
        cursor.position += length;
        return buffer.array();
    }

    private IllegalStateException indexError(Exception error) {
        if (error instanceof IllegalStateException state && state.getMessage() != null
                && state.getMessage().startsWith("FTDC")) return state;
        return new IllegalStateException("FTDC 索引读取失败：" + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()), error);
    }

    @FunctionalInterface
    public interface BlockConsumer {
        void accept(FtdcBlockIndex block);
    }

    public record MetricBlockIndex(
            int fileId,
            int blockOrdinal,
            long fileOffset,
            int documentLength,
            int declaredLength,
            int compressedLength,
            int schemaId,
            int numDeltas,
            long startEpochMillis,
            long endEpochMillis,
            long pointOffset,
            long metricBaseline,
            int metricDeltaOffset,
            int metricZerosAtStart,
            long timeBaseline,
            int timeDeltaOffset,
            int timeZerosAtStart
    ) {
        public long pointCount() {
            return (long) numDeltas + 1;
        }
    }

    private static final class Cursor {
        private long position;
        private int fileCount;
        private int schemaCount;
        private int blockCount;
    }

    public record GroupMetricBlockIndex(
            int fileId, int blockOrdinal, long fileOffset, int documentLength, int declaredLength,
            int compressedLength, int numAttributes, int numDeltas, long startEpochMillis, long endEpochMillis, long pointOffset,
            int[] metricIndexes, long[] metricBaselines, int[] metricDeltaOffsets, int[] metricZerosAtStart,
            long timeBaseline, int timeDeltaOffset, int timeZerosAtStart
    ) {
        public GroupMetricBlockIndex {
            metricIndexes = metricIndexes.clone();
            metricBaselines = metricBaselines.clone();
            metricDeltaOffsets = metricDeltaOffsets.clone();
            metricZerosAtStart = metricZerosAtStart.clone();
            int size = metricIndexes.length;
            if (size != metricBaselines.length || size != metricDeltaOffsets.length || size != metricZerosAtStart.length) {
                throw new IllegalArgumentException("FTDC 分组指标索引长度不一致");
            }
        }
    }

    public record GroupQueryIndex(List<FtdcIndexWriter.SourceFile> files, List<FtdcSchema> schemas,
                                  List<GroupMetricBlockIndex> blocks) {
        public GroupQueryIndex {
            files = List.copyOf(files);
            schemas = List.copyOf(schemas);
            blocks = List.copyOf(blocks);
        }
    }
}
