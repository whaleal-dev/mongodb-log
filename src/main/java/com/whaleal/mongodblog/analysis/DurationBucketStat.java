package com.whaleal.mongodblog.analysis;

public record DurationBucketStat(
        String key,
        String label,
        long count,
        double percentage,
        long totalDurationMillis,
        double averageDurationMillis,
        long maxDurationMillis
) {
}

