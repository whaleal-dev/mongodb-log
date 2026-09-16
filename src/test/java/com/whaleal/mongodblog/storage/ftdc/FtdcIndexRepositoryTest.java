package com.whaleal.mongodblog.storage.ftdc;

import com.whaleal.mongodblog.parser.ftdc.FtdcSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
