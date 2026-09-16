package com.whaleal.mongodblog.analysis;

public record PatternStat(
        String namespace,
        String operation,
        String pattern,
        String planSummary,
        long count,
        long totalDurationMillis,
        double averageDurationMillis,
        long minDurationMillis,
        long maxDurationMillis,
        long totalCpuNanos,
        boolean cpuAvailable,
        String slowestQueryId,
        SlowQueryRecord slowestQuery
) {
}
