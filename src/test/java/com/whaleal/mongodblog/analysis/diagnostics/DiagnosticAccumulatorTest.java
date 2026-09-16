package com.whaleal.mongodblog.analysis.diagnostics;

import com.whaleal.mongodblog.parser.ParseOutcome;
import com.whaleal.mongodblog.parser.ParsedLogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticAccumulatorTest {
    @Test
    void aggregatesEventsConnectionsReplicationAndSlowQuerySignalsWithoutChangingRawEntries() {
        DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
        accumulator.accept(success(entry(1, 1_720_000_000_000L, "W", "ACCESS", 5626700,
                "Client has attempted to reauthenticate as a single user", Map.of("user", Map.of("user", "secret", "db", "admin")), false)));
        accumulator.accept(success(entry(2, 1_720_000_001_000L, "I", "NETWORK", 22943,
                "Connection accepted", Map.of("connectionCount", 12, "remote", "10.1.2.3:40100"), false)));
        accumulator.accept(success(entry(3, 1_720_000_002_000L, "I", "NETWORK", 51800,
                "client metadata", Map.of("doc", Map.of(
                        "application", Map.of("name", "orders-service"),
                        "driver", Map.of("name", "mongo-java-driver", "version", "4.11.0"))), false)));
        accumulator.accept(success(entry(4, 1_720_000_003_000L, "I", "REPL_HB", 23974,
                "Heartbeat failed after max retries", Map.of("target", "db2.example:27017"), false)));
        accumulator.accept(success(new ParsedLogEntry(
                5, 0, 1_720_000_004_000L, "I", "COMMAND", 51803, "conn5", "Slow query",
                "sales.orders", "find", 500L, 10_000_000L, 1024L, "COLLSCAN", "10.1.2.3",
                "{\"status\":\"?\"}", "raw slow query", Map.of(
                        "docsExamined", 1000,
                        "keysExamined", 0,
                        "nreturned", 10,
                        "queryHash", "ABC123",
                        "planCacheKey", "KEY123",
                        "planningTimeMicros", 2_000,
                        "waitForWriteConcernDurationMillis", 15,
                        "storage", Map.of("data", Map.of("bytesRead", 4096, "timeReadingMicros", 300_000))),
                true, false
        )));

        LogDiagnostics result = accumulator.finish();

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.totalParsedLines()).isEqualTo(5);
        assertThat(result.severityCounts()).containsEntry("W", 1L).containsEntry("I", 4L);
        assertThat(result.abnormalEvents()).singleElement().satisfies(event -> {
            assertThat(event.messageId()).isEqualTo(5626700);
            assertThat(event.count()).isEqualTo(1);
            assertThat(event.samples()).allSatisfy(sample -> assertThat(sample.message()).doesNotContain("secret"));
        });
        assertThat(result.connections().accepted()).isEqualTo(1);
        assertThat(result.connections().connectionCountMax()).isEqualTo(12);
        assertThat(result.connections().applications()).containsEntry("orders-service", 1L);
        assertThat(result.connections().drivers()).containsEntry("mongo-java-driver 4.11.0", 1L);
        assertThat(result.replicationEvents()).singleElement()
                .satisfies(event -> assertThat(event.type()).isEqualTo("HEARTBEAT_FAILURE"));
        assertThat(result.slowQueries().collscanCount()).isEqualTo(1);
        assertThat(result.slowQueries().highDocumentScanRatioCount()).isEqualTo(1);
        assertThat(result.slowQueries().storageDominantCount()).isEqualTo(1);
        assertThat(result.slowQueries().writeConcernWaitCount()).isEqualTo(1);
        assertThat(result.slowQueries().fieldCoverage()).containsEntry("queryHash", 1L);
        assertThat(result.slowQueries().insights()).singleElement().satisfies(insight -> {
            assertThat(insight.queryPattern()).isEqualTo("{\"status\":\"?\"}");
            assertThat(insight.reasons()).contains("COLLSCAN", "扫描文档／返回比高", "磁盘读取耗时占比高", "写关注等待");
        });
        assertThat(result.timeline()).hasSize(1);
    }

    @Test
    void compactsTimelineAndCountsOutOfOrderAndEnvelopeCoverage() {
        DiagnosticAccumulator accumulator = new DiagnosticAccumulator(2);
        accumulator.accept(success(new ParsedLogEntry(
                1, 0, 7_200_000L, "I", "CONTROL", 1, "main", "Build Info", null, "control",
                null, null, null, null, null, "{}", "{}", Map.of("buildInfo", Map.of("version", "8.0.1")),
                false, false, new LogEnvelopeMetadata("R", List.of("startupWarnings"), true)
        )));
        accumulator.accept(success(entry(2, 0L, "I", "NETWORK", 2, "Connection ended", Map.of(), false)));
        accumulator.accept(success(entry(3, 3_600_000L, "I", "NETWORK", 3, "Connection ended", Map.of(), false)));

        LogDiagnostics result = accumulator.finish();

        assertThat(result.timeline()).hasSizeLessThanOrEqualTo(2);
        assertThat(result.timelineBucketMillis()).isGreaterThanOrEqualTo(3_600_000L);
        assertThat(result.dataQuality().outOfOrderLines()).isEqualTo(1);
        assertThat(result.dataQuality().truncatedLines()).isEqualTo(1);
        assertThat(result.dataQuality().taggedLines()).isEqualTo(1);
        assertThat(result.dataQuality().services()).containsEntry("R", 1L);
        assertThat(result.dataQuality().serverVersions()).containsEntry("8.0.1", 1L);
    }

    private ParseOutcome success(ParsedLogEntry entry) {
        return ParseOutcome.success(entry);
    }

    private ParsedLogEntry entry(long line, long timestamp, String severity, String component, int id,
                                 String message, Map<String, Object> attributes, boolean slow) {
        return new ParsedLogEntry(line, 0, timestamp, severity, component, id, "ctx", message,
                null, component.toLowerCase(), null, null, null, null, null, "{}", message,
                attributes, slow, message.contains("Heartbeat failed"));
    }
}
