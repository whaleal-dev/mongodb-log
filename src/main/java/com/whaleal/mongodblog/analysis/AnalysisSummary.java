package com.whaleal.mongodblog.analysis;

import java.util.List;
import java.util.Map;

public record AnalysisSummary(
        long totalLines,
        long successLines,
        long partialLines,
        long skippedLines,
        long failedLines,
        long slowQueryCount,
        long totalSlowDurationMillis,
        long heartbeatFailures,
        boolean cpuAvailable,
        Map<String, Long> parseErrors,
        List<DurationBucketStat> durationDistribution,
        Map<String, AggregateStat> operations,
        Map<String, AggregateStat> namespaces,
        Map<String, AggregateStat> patterns,
        Map<String, AggregateStat> plans,
        Map<String, AggregateStat> remotes,
        Map<String, AggregateStat> cpuByOperationNamespace,
        List<PatternStat> patternStats,
        Map<String, Long> namespaceResponseBytes,
        Map<String, Map<String, Long>> cpuByOperationBuckets,
        List<ConnectionAverage> averageConnections,
        Long logStartEpochMillis,
        Long logEndEpochMillis
) {
}
