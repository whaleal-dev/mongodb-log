package com.whaleal.mongodblog.parser.ftdc;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonType;
import org.bson.BsonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FtdcBaselineFlattener {
    public FlattenedBaseline flatten(BsonDocument document) {
        List<String> paths = new ArrayList<>();
        List<Long> values = new ArrayList<>();
        flattenDocument(document, "", paths, values);
        long[] primitiveValues = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            primitiveValues[i] = values.get(i);
        }
        return new FlattenedBaseline(new FtdcSchema(paths), primitiveValues);
    }

    private void flattenDocument(BsonDocument document, String prefix, List<String> paths, List<Long> values) {
        for (Map.Entry<String, BsonValue> entry : document.entrySet()) {
            flattenValue(entry.getValue(), join(prefix, entry.getKey()), paths, values);
        }
    }

    private void flattenArray(BsonArray array, String prefix, List<String> paths, List<Long> values) {
        for (int i = 0; i < array.size(); i++) {
            flattenValue(array.get(i), join(prefix, Integer.toString(i)), paths, values);
        }
    }

    private void flattenValue(BsonValue value, String path, List<String> paths, List<Long> values) {
        if (value.isDocument()) {
            flattenDocument(value.asDocument(), path, paths, values);
        } else if (value.isArray()) {
            flattenArray(value.asArray(), path, paths, values);
        } else if (value.isTimestamp()) {
            paths.add(path + "/t");
            values.add((long) value.asTimestamp().getTime());
            paths.add(path + "/i");
            values.add((long) value.asTimestamp().getInc());
        } else {
            Long number = numericValue(value);
            if (number != null) {
                paths.add(path);
                values.add(number);
            }
        }
    }

    private Long numericValue(BsonValue value) {
        BsonType type = value.getBsonType();
        return switch (type) {
            case INT32 -> (long) value.asInt32().getValue();
            case INT64 -> value.asInt64().getValue();
            case DOUBLE -> doubleToLong(value.asDouble().getValue());
            case DECIMAL128 -> value.asDecimal128().getValue().longValue();
            case DATE_TIME -> value.asDateTime().getValue();
            case BOOLEAN -> value.asBoolean().getValue() ? 1L : 0L;
            default -> null;
        };
    }

    private long doubleToLong(double value) {
        if (Double.isNaN(value)) return 0;
        if (value >= 0x1.0p63) return Long.MAX_VALUE;
        if (value < -0x1.0p63) return Long.MIN_VALUE;
        return (long) value;
    }

    private String join(String prefix, String segment) {
        return prefix.isEmpty() ? segment : prefix + "/" + segment;
    }

    public record FlattenedBaseline(FtdcSchema schema, long[] values) {
    }
}
