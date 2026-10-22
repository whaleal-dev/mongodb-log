package com.whaleal.mongodblog.storage.ftdc;

import com.whaleal.mongodblog.parser.ftdc.FtdcSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtdcIndexRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void writesAndReadsLittleEndianIndexWithMultipleSchemasAndFiles() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source"));
        Files.writeString(source.resolve("0000-metrics.a"), "first");
        Files.writeString(source.resolve("0001-metrics.b"), "second");
        List<FtdcIndexWriter.SourceFile> files = List.of(
                FtdcIndexWriter.SourceFile.from(0, "0000-metrics.a", source.resolve("0000-metrics.a"), 1),
                FtdcIndexWriter.SourceFile.from(1, "0001-metrics.b", source.resolve("0001-metrics.b"), 1)
        );
        List<FtdcSchema> schemas = List.of(
                new FtdcSchema(List.of("start", "a")),
                new FtdcSchema(List.of("start", "b"))
        );
        List<FtdcBlockIndex> blocks = List.of(
                new FtdcBlockIndex(0, 0, 0, 100, 80, 60, 0, 1,
                        1_000, 2_000, 0, new long[]{1_000, 5}, new int[]{20, 23}, new int[]{0, 0}),
                new FtdcBlockIndex(1, 0, 0, 110, 90, 70, 1, 1,
                        3_000, 4_000, 2, new long[]{3_000, 9}, new int[]{20, 23}, new int[]{0, 1})
        );
        Path target = directory.resolve("blocks.idx");

        new FtdcIndexWriter().write(target, files, schemas, blocks);
        FtdcIndexReader reader = new FtdcIndexReader(target);

        assertThat(reader.files()).containsExactlyElementsOf(files);
        assertThat(reader.schemas()).containsExactlyElementsOf(schemas);
        assertThat(reader.blocks()).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(blocks);
        assertThat(Files.exists(directory.resolve("blocks.idx.tmp"))).isFalse();
        reader.validateSources(source);
    }

    @Test
    void rejectsWrongMagicTruncationAndChangedSource() throws Exception {
        Path source = Files.createDirectories(directory.resolve("source"));
        Path sourceFile = source.resolve("0000-metrics.a");
        Files.writeString(sourceFile, "first");
        FtdcIndexWriter.SourceFile metadata = FtdcIndexWriter.SourceFile.from(0, sourceFile.getFileName().toString(), sourceFile, 0);
        Path target = directory.resolve("blocks.idx");
        new FtdcIndexWriter().write(target, List.of(metadata), List.of(), List.of());

        byte[] bytes = Files.readAllBytes(target);
        bytes[0] ^= 1;
        Path wrongMagic = directory.resolve("wrong.idx");
        Files.write(wrongMagic, bytes);
        assertThatThrownBy(() -> new FtdcIndexReader(wrongMagic).files())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("magic");

        Path truncated = directory.resolve("truncated.idx");
        Files.write(truncated, java.util.Arrays.copyOf(bytes, 12));
        assertThatThrownBy(() -> new FtdcIndexReader(truncated).files())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("索引");

        Files.writeString(sourceFile, "changed");
        assertThatThrownBy(() -> new FtdcIndexReader(target).validateSources(source))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("校验失败");
    }

    @Test
    void buildsStableMetricCatalogFromSchemaUnion() {
        FtdcCatalog catalog = FtdcCatalog.create(List.of(
                new FtdcSchema(List.of("start", "server/a")),
                new FtdcSchema(List.of("start", "server/b"))
        ), 2, 8, 1_000L, 8_000L);

        assertThat(catalog.metrics()).extracting(FtdcCatalog.Metric::path)
                .containsExactly("start", "server/a", "server/b");
        assertThat(catalog.metrics()).extracting(FtdcCatalog.Metric::metricId).doesNotHaveDuplicates();
        assertThat(catalog.metrics().get(0).schemaIds()).containsExactly(0, 1);
    }

    @Test
    void acceptsTwentySourcesAndRejectsAnIndexExceedingTheTaskFileLimit() throws Exception {
        List<FtdcIndexWriter.SourceFile> files = IntStream.range(0, 21)
                .mapToObj(order -> new FtdcIndexWriter.SourceFile(order, "source-" + order, 0, "", 0)).toList();
        Path valid = directory.resolve("twenty.idx");
        new FtdcIndexWriter().write(valid, files.subList(0, 20), List.of(), List.of());
        assertThat(new FtdcIndexReader(valid).files()).hasSize(20);
        Path invalid = directory.resolve("twenty-one.idx");
        new FtdcIndexWriter().write(invalid, files, List.of(), List.of());
        assertThatThrownBy(() -> new FtdcIndexReader(invalid).files())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("文件");
    }

    @ParameterizedTest
    @ValueSource(strings = {"files", "schemas", "paths", "string", "block"})
    void rejectsTinyCorruptIndexesBeforeLargeAllocations(String kind) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("MLOGFTDC".getBytes(StandardCharsets.US_ASCII)).putInt(1);
        bytes.putInt(kind.equals("files") ? 10_000_000 : kind.equals("string") ? 1 : 0);
        bytes.putInt(kind.equals("schemas") ? 10_000_000 : kind.equals("paths") ? 1 : 0);
        bytes.putInt(kind.equals("block") ? 1 : 0);
        if (kind.equals("paths")) bytes.putInt(0).putInt(10_000_000);
        if (kind.equals("string")) bytes.putInt(0).putInt(16 * 1024 * 1024).put(new byte[16]);
        if (kind.equals("block")) {
            bytes.putInt(0).putInt(0).putLong(0).putInt(100).putInt(80).putInt(60)
                    .putInt(0).putInt(1_000_000).putInt(0).putLong(1).putLong(1).putLong(0);
        }
        Path index = directory.resolve(kind + ".idx");
        Files.write(index, java.util.Arrays.copyOf(bytes.array(), bytes.position()));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx12m", "-cp", System.getProperty("java.class.path"),
                AllocationProbe.class.getName(), index.toString(), kind).redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("corrupt index reader must finish").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).contains("rejected:");
        } finally { process.destroyForcibly(); }
    }

    public static class AllocationProbe {
        public static void main(String[] args) {
            FtdcIndexReader reader = new FtdcIndexReader(Path.of(args[0]));
            try {
                switch (args[1]) {
                    case "schemas", "paths" -> reader.schemas();
                    case "block" -> reader.blocks();
                    default -> reader.files();
                }
                throw new AssertionError("Corrupt index was accepted");
            } catch (IllegalStateException expected) {
                System.out.println("rejected:" + expected.getMessage());
            }
        }
    }
}
