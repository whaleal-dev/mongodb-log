package com.whaleal.mongodblog.analysis;

import java.util.Map;

public record SlowQueryRecord(
        String queryId,
        int fileIndex,
        long lineNumber,
        long timestampEpochMillis,
        String operation,
        String namespace,
        long durationMillis,
        Long cpuNanos,
        Long responseLength,
        String planSummary,
        String remote,
        String queryPattern,
        String rawLine,
        Map<String, Object> attributes
) {
}

