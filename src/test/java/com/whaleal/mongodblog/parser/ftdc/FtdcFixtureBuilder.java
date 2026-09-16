package com.whaleal.mongodblog.parser.ftdc;

import org.bson.BsonBinary;
import org.bson.BsonBinaryWriter;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.EncoderContext;
import org.bson.io.BasicOutputBuffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

public final class FtdcFixtureBuilder {
    private FtdcFixtureBuilder() {
    }

    public static byte[] file(BsonDocument... documents) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            for (BsonDocument document : documents) {
                output.write(encode(document));
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return output.toByteArray();
    }

    public static BsonDocument metadata() {
        return new BsonDocument("type", new BsonInt32(0));
    }

    public static BsonDocument block(BsonDocument baseline, List<long[]> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values 不能为空");
        }
        int pointCount = values.get(0).length;
        if (pointCount < 1 || values.stream().anyMatch(row -> row.length != pointCount)) {
            throw new IllegalArgumentException("所有指标必须包含相同且非空的点数");
        }
        ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
        try {
            uncompressed.write(encode(baseline));
            uncompressed.write(leInt(values.size()));
            uncompressed.write(leInt(pointCount - 1));
            writeDeltas(uncompressed, values, pointCount);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] payload = uncompressed.toByteArray();
        return new BsonDocument("type", new BsonInt32(1))
                .append("data", new BsonBinary(compress(payload)));
    }

    public static byte[] compressedBlockData(BsonDocument baseline, int metricCount, int deltaCount,
                                             byte[] encodedDeltas) {
        ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
        try {
            uncompressed.write(encode(baseline));
            uncompressed.write(leInt(metricCount));
            uncompressed.write(leInt(deltaCount));
            uncompressed.write(encodedDeltas);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return compress(uncompressed.toByteArray());
    }

    public static byte[] compress(byte[] payload) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try {
            compressed.write(leInt(payload.length));
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) {
                deflater.write(payload);
            }
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return compressed.toByteArray();
    }

    private static void writeDeltas(ByteArrayOutputStream output, List<long[]> values, int pointCount) {
        long zeroRun = 0;
        for (long[] metric : values) {
            for (int point = 1; point < pointCount; point++) {
                long delta = metric[point] - metric[point - 1];
                if (delta == 0) {
                    zeroRun++;
                    continue;
                }
                flushZeros(output, zeroRun);
                zeroRun = 0;
                writeUVarInt(output, delta);
            }
        }
        flushZeros(output, zeroRun);
    }

    private static void flushZeros(ByteArrayOutputStream output, long count) {
        if (count == 0) {
            return;
        }
        writeUVarInt(output, 0);
        writeUVarInt(output, count - 1);
    }

    private static void writeUVarInt(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7fL) != 0) {
            output.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        output.write((int) value);
    }

    private static byte[] encode(BsonDocument document) {
        BasicOutputBuffer buffer = new BasicOutputBuffer();
        try (BsonBinaryWriter writer = new BsonBinaryWriter(buffer)) {
            new BsonDocumentCodec().encode(writer, document, EncoderContext.builder().build());
        }
        return buffer.toByteArray();
    }

    private static byte[] leInt(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }
}
