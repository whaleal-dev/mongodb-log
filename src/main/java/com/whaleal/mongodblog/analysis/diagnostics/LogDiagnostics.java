package com.whaleal.mongodblog.analysis.diagnostics;

import java.util.List;
import java.util.Map;

public record LogDiagnostics(
        int schemaVersion,
        long totalParsedLines,
        Map<String, Long> severityCounts,
        Map<String, Long> componentCounts,
        List<EventStat> abnormalEvents,
        List<DiagnosticTimeBucket> timeline,
        long timelineBucketMillis,
        ConnectionDiagnostics connections,
        List<ReplicationEventStat> replicationEvents,
        SlowQueryDiagnostics slowQueries,
        DataQualityDiagnostics dataQuality
) {
    public LogDiagnostics {
        severityCounts = Map.copyOf(severityCounts);
        componentCounts = Map.copyOf(componentCounts);
        abnormalEvents = List.copyOf(abnormalEvents);
        timeline = List.copyOf(timeline);
        replicationEvents = List.copyOf(replicationEvents);
    }

    public record EventSample(long timestampEpochMillis, String message) {
    }

    public record EventStat(
            String key,
            String severity,
            String component,
            Integer messageId,
            String message,
            long count,
            long firstEpochMillis,
            long lastEpochMillis,
            List<EventSample> samples
    ) {
        public EventStat {
            samples = List.copyOf(samples);
        }
    }

    public record DiagnosticTimeBucket(
            long epochMillis,
            long warnings,
            long errors,
            long fatals,
            long connectionsAccepted,
            long connectionsEnded,
            long replicationEvents
    ) {
    }

    public record ConnectionDiagnostics(
            long accepted,
            long ended,
            long authenticationSucceeded,
            long notAuthenticating,
            long reauthenticationWarnings,
            long connectionCountSamples,
            Long connectionCountMin,
            Long connectionCountMax,
            Double connectionCountAverage,
            Map<String, Long> applications,
            Map<String, Long> drivers,
            boolean topValuesApproximate
    ) {
        public ConnectionDiagnostics {
            applications = Map.copyOf(applications);
            drivers = Map.copyOf(drivers);
        }
    }

    public record ReplicationEventStat(
            String type,
            String label,
            String component,
            Integer messageId,
            String message,
            long count,
            long firstEpochMillis,
            long lastEpochMillis
    ) {
    }

    public record SlowQueryDiagnostics(
            long total,
            long collscanCount,
            long highDocumentScanRatioCount,
            long highIndexScanRatioCount,
            long zeroReturnHighScanCount,
            long storageDominantCount,
            long planningDominantCount,
            long writeConcernWaitCount,
            long flowControlWaitCount,
            long lockWaitCount,
            long remoteOpWaitCount,
            long authorizationWaitCount,
            long queueWaitCount,
            long oplogSlotWaitCount,
            long hasSortStageCount,
            long usedDiskCount,
            long spillCount,
            long distinctShapeCount,
            Map<String, Long> fieldCoverage,
            Map<String, Long> queryFrameworks,
            List<SlowQueryInsight> insights
    ) {
        public SlowQueryDiagnostics {
            fieldCoverage = Map.copyOf(fieldCoverage);
            queryFrameworks = Map.copyOf(queryFrameworks);
            insights = List.copyOf(insights);
        }
    }

    public record SlowQueryInsight(
            long timestampEpochMillis,
            String namespace,
            String operation,
            String queryPattern,
            String shapeHash,
            String planCacheKey,
            String planSummary,
            long durationMillis,
            Long docsExamined,
            Long keysExamined,
            Long returned,
            Double documentsPerReturned,
            Double keysPerReturned,
            Double storageReadMillis,
            Double planningMillis,
            Long writeConcernWaitMillis,
            Double flowControlWaitMillis,
            Double lockWaitMillis,
            List<String> reasons
    ) {
        public SlowQueryInsight {
            reasons = List.copyOf(reasons);
        }
    }

    public record DataQualityDiagnostics(
            long structuredLines,
            long legacyLines,
            long truncatedLines,
            long taggedLines,
            long outOfOrderLines,
            Map<String, Long> services,
            Map<String, Long> serverVersions
    ) {
        public DataQualityDiagnostics {
            services = Map.copyOf(services);
            serverVersions = Map.copyOf(serverVersions);
        }
    }
}
