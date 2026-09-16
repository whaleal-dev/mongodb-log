package com.whaleal.mongodblog.analysis;

public record AggregateStat(
        long count,
        long totalDurationMillis,
        double averageDurationMillis,
        long minDurationMillis,
        long maxDurationMillis,
        long totalResponseBytes,
        long totalCpuNanos
) {
}

