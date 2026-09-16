package com.whaleal.mongodblog.parser.ftdc;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtdcFileReaderTest {
    @TempDir
    Path directory;

    @Test
    void scansConsecutiveMetadataAndMetricDocumentsWithOffsets() throws Exception {
        BsonDocument block = FtdcFixtureBuilder.block(
                new BsonDocument("start", new org.bson.BsonDateTime(1_700_000_000_000L)),
                List.of(new long[]{1_700_000_000_000L, 1_700_000_001_000L})
        );
        byte[] metadata = FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata());
        Path file = directory.resolve("metrics.test");
        Files.write(file, FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata(), block));

        List<FtdcDocument> documents = new ArrayList<>();
        FtdcFileReader.ScanSummary summary = new FtdcFileReader().scan(file, documents::add);

        assertThat(summary.documentCount()).isEqualTo(2);
        assertThat(summary.metricsBlockCount()).isEqualTo(1);
        assertThat(documents).extracting(FtdcDocument::type).containsExactly(0, 1);
        assertThat(documents.get(0).fileOffset()).isZero();
        assertThat(documents.get(1).fileOffset()).isEqualTo(metadata.length);
        assertThat(documents.get(1).data()).isNotEmpty();
    }

    @Test
    void rejectsFilesWithoutMetricBlocks() throws Exception {
        Path file = directory.resolve("metadata-only");
        Files.write(file, FtdcFixtureBuilder.file(FtdcFixtureBuilder.metadata()));

        assertThatThrownBy(() -> new FtdcFileReader().scan(file, ignored -> { }))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("type=1");
    }

    @Test
    void rejectsIllegalLengthAndTruncatedDocument() throws Exception {
        Path illegal = directory.resolve("illegal");
        Files.write(illegal, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).array());
        assertThatThrownBy(() -> new FtdcFileReader().scan(illegal, ignored -> { }))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("BSON 长度");

        byte[] valid = FtdcFixtureBuilder.file(new BsonDocument("type", new BsonInt32(1)));
        Path truncated = directory.resolve("truncated");
        Files.write(truncated, java.util.Arrays.copyOf(valid, valid.length - 1));
        assertThatThrownBy(() -> new FtdcFileReader().scan(truncated, ignored -> { }))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("截断");
    }
}
