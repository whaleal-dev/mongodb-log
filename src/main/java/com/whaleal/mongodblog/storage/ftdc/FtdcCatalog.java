package com.whaleal.mongodblog.storage.ftdc;

import com.whaleal.mongodblog.parser.ftdc.FtdcSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FtdcCatalog(
        List<Metric> metrics,
        List<Schema> schemas,
        int blockCount,
        long sampleCount,
        Long startEpochMillis,
        Long endEpochMillis
) {
    public FtdcCatalog {
        metrics = List.copyOf(metrics);
        schemas = List.copyOf(schemas);
    }

    public static FtdcCatalog create(List<FtdcSchema> inputSchemas, int blockCount, long sampleCount,
                                     Long startEpochMillis, Long endEpochMillis) {
        List<Schema> schemas = new ArrayList<>();
        Map<String, List<Integer>> metricSchemas = new LinkedHashMap<>();
        for (int schemaId = 0; schemaId < inputSchemas.size(); schemaId++) {
            List<String> paths = inputSchemas.get(schemaId).paths();
            schemas.add(new Schema(schemaId, paths));
            for (String path : paths) {
                metricSchemas.computeIfAbsent(path, ignored -> new ArrayList<>()).add(schemaId);
            }
        }
        List<Metric> metrics = metricSchemas.entrySet().stream()
                .map(entry -> new Metric(metricId(entry.getKey()), entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
        return new FtdcCatalog(metrics, schemas, blockCount, sampleCount, startEpochMillis, endEpochMillis);
    }

    public Metric requireMetric(String metricId) {
        return metrics.stream().filter(metric -> metric.metricId().equals(metricId)).findFirst()
                .orElseThrow(() -> new java.util.NoSuchElementException("FTDC 指标不存在：" + metricId));
    }

    public Schema requireSchema(int schemaId) {
        return schemas.stream().filter(schema -> schema.schemaId() == schemaId).findFirst()
                .orElseThrow(() -> new IllegalStateException("FTDC schema 不存在：" + schemaId));
    }

    private static String metricId(String path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                id.append("%02x".formatted(digest[i]));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Metric(String metricId, String path, List<Integer> schemaIds) {
        public Metric {
            schemaIds = List.copyOf(schemaIds);
        }
    }

    public record Schema(int schemaId, List<String> paths) {
        public Schema {
            paths = List.copyOf(paths);
        }

        public int indexOf(String path) {
            return paths.indexOf(path);
        }
    }
}
