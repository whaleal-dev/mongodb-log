package com.whaleal.mongodblog.parser.ftdc;

import org.bson.BsonArray;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonTimestamp;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtdcBlockScannerTest {
    @Test
    void flattensSupportedBsonTypesAndIndexesPositiveNegativeAndRleDeltas() {
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1_700_000_000_000L))
                .append("nested", new BsonDocument("i32", new BsonInt32(10))
                        .append("i64", new BsonInt64(100))
                        .append("flag", BsonBoolean.TRUE)
                        .append("ts", new BsonTimestamp(7, 3))
                        .append("ignored", new BsonString("text")))
                .append("array", new BsonArray(List.of(new BsonInt32(4), new BsonInt64(8))));
        List<long[]> values = List.of(
                new long[]{1_700_000_000_000L, 1_700_000_001_000L, 1_700_000_002_000L},
                new long[]{10, 12, 12},
                new long[]{100, 97, 97},
                new long[]{1, 1, 1},
                new long[]{7, 8, 8},
                new long[]{3, 3, 4},
                new long[]{4, 4, 4},
                new long[]{8, 9, 10}
        );
        byte[] data = FtdcFixtureBuilder.block(baseline, values).getBinary("data").getData();

        FtdcBlockScanner.ScannedBlock block = new FtdcBlockScanner().scan(data, 123, 4);

        assertThat(block.schema().paths()).containsExactly(
                "start", "nested/i32", "nested/i64", "nested/flag", "nested/ts/t", "nested/ts/i", "array/0", "array/1"
        );
        assertThat(block.baseline()).containsExactly(
                1_700_000_000_000L, 10, 100, 1, 7, 3, 4, 8
        );
        assertThat(block.numDeltas()).isEqualTo(2);
        assertThat(block.startEpochMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(block.endEpochMillis()).isEqualTo(1_700_000_002_000L);
        assertThat(block.zerosAtStart()).hasSize(8);
    }

    @Test
    void keepsJavaLongWraparoundSemantics() {
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1))
                .append("counter", new BsonInt64(Long.MAX_VALUE));
        byte[] data = FtdcFixtureBuilder.block(baseline, List.of(
                new long[]{1, 2}, new long[]{Long.MAX_VALUE, Long.MIN_VALUE}
        )).getBinary("data").getData();

        FtdcBlockScanner.ScannedBlock block = new FtdcBlockScanner().scan(data, 0, 0);
        assertThat(block.lastValues()[1]).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void rejectsAttributeMismatchAndCorruptCompression() {
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1));
        byte[] valid = FtdcFixtureBuilder.block(baseline, List.of(new long[]{1, 2})).getBinary("data").getData();
        byte[] mismatch = valid.clone();
        // Declared uncompressed payload is zlib-compressed, so create mismatch through the fixture shape.
        BsonDocument badShape = FtdcFixtureBuilder.block(baseline, List.of(new long[]{1, 2}, new long[]{2, 3}));

        assertThatThrownBy(() -> new FtdcBlockScanner().scan(badShape.getBinary("data").getData(), 0, 0))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("属性数量");

        valid[valid.length - 1] ^= 0x7f;
        assertThatThrownBy(() -> new FtdcBlockScanner().scan(valid, 19, 2))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("block 2");
    }

    @Test
    void rejectsExcessiveSamplesAndMetricSampleProductBeforeDecodingRle() {
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1));
        byte[] excessiveSamples = FtdcFixtureBuilder.compressedBlockData(
                baseline, 1, FtdcBlockScanner.MAX_SAMPLES_PER_BLOCK, new byte[]{0, 0});
        byte[] excessiveProduct = FtdcFixtureBuilder.compressedBlockData(
                baseline, 10_000, 100, new byte[]{0, 0});

        assertThatThrownBy(() -> new FtdcBlockScanner().scan(excessiveSamples, 0, 0))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("样本数量非法");
        assertThatThrownBy(() -> new FtdcBlockScanner().scan(excessiveProduct, 0, 0))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("指标与样本乘积");
    }

    @Test
    void rejectsTrailingCompressedDataAndDeclaredLengthMismatch() {
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1));
        byte[] valid = FtdcFixtureBuilder.block(baseline, List.of(new long[]{1, 2}))
                .getBinary("data").getData();
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        trailing[trailing.length - 1] = 7;
        byte[] truncated = Arrays.copyOf(valid, valid.length - 2);
        byte[] wrongLength = valid.clone();
        wrongLength[0]++;

        assertThatThrownBy(() -> new FtdcBlockScanner().scan(trailing, 0, 0))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("剩余压缩数据");
        assertThatThrownBy(() -> new FtdcBlockScanner().scan(wrongLength, 0, 0))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("实际解压长度");
        assertThatThrownBy(() -> new FtdcBlockScanner().scan(truncated, 0, 0))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("zlib");
    }

    @Test
    void rejectsRleThatExceedsTheDeclaredMetricCells() {
        BsonDocument baseline = new BsonDocument("start", new BsonDateTime(1));
        byte[] invalidRle = FtdcFixtureBuilder.compressedBlockData(
                baseline, 1, 1, new byte[]{0, 2});

        assertThatThrownBy(() -> new FtdcBlockScanner().scan(invalidRle, 0, 0))
                .isInstanceOf(FtdcFormatException.class)
                .hasMessageContaining("零游程超出声明");
    }
}
