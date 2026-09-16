package com.whaleal.mongodblog.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.parser.CompositeLogParser;
import com.whaleal.mongodblog.parser.LegacyLogParser;
import com.whaleal.mongodblog.parser.QueryPatternNormalizer;
import com.whaleal.mongodblog.parser.StructuredLogParser;
import com.whaleal.mongodblog.parser.ParseOutcome;
import com.whaleal.mongodblog.parser.ParsedLogEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisAccumulatorTest {

    @Test
    void groupsPatternsByNamespaceAndOperationAndRanksByCount() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(2);
        accumulator.accept(ParseOutcome.success(entry(1, "find", "db.a", 100L, 0L, 20L, "same", "IXSCAN", "ip", false)));
        accumulator.accept(ParseOutcome.success(entry(2, "find", "db.a", 200L, null, 30L, "same", "COLLSCAN", "ip", false)));
        accumulator.accept(ParseOutcome.success(entry(3, "find", "db.b", 1_000L, null, 40L, "same", "COLLSCAN", "ip", false)));
        accumulator.accept(ParseOutcome.success(entry(4, "update", "db.a", 900L, 3L, 40L, "same", "IXSCAN", "ip", false)));

        JsonNode stats = json(accumulator.finish()).path("patternStats");
        assertThat(stats.size()).isEqualTo(3);
        JsonNode first = stats.path(0);
        assertThat(first.path("namespace").asText()).isEqualTo("db.a");
        assertThat(first.path("operation").asText()).isEqualTo("find");
        assertThat(first.path("pattern").asText()).isEqualTo("same");
        assertThat(first.path("count").asLong()).isEqualTo(2);
        assertThat(first.path("totalDurationMillis").asLong()).isEqualTo(300);
        assertThat(first.path("averageDurationMillis").asDouble()).isEqualTo(150);
        assertThat(first.path("minDurationMillis").asLong()).isEqualTo(100);
        assertThat(first.path("maxDurationMillis").asLong()).isEqualTo(200);
        assertThat(first.path("planSummary").asText()).contains("IXSCAN", "COLLSCAN");
        assertThat(first.path("cpuAvailable").asBoolean()).isTrue();
        assertThat(first.path("totalCpuNanos").asLong()).isZero();
        assertThat(first.path("slowestQueryId").isNull()).isTrue();
        assertThat(first.path("slowestQuery").path("queryId").asText()).isEqualTo("0-2");
        assertThat(first.path("slowestQuery").path("rawLine").asText()).isEqualTo("raw-2");
        assertThat(StreamSupport.stream(stats.spliterator(), false)
                .filter(row -> row.path("namespace").asText().equals("db.b"))
                .findFirst().orElseThrow().path("slowestQueryId").asText()).isEqualTo("0-3");
        assertThat(json(accumulator.finish()).toString()).doesNotContain("raw-1");
    }

    @Test
    void limitsPatternStatsToFiftyByFrequencyAndKeepsSeparateNamespaces() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(2);
        for (int index = 0; index < 51; index++) {
            accumulator.accept(ParseOutcome.success(entry(index + 1, "find", "db." + index, 1_000L, null, 1L, "same", "plan", "ip", false)));
        }
        accumulator.accept(ParseOutcome.success(entry(100, "find", "db.frequent", 1L, null, 1L, "same", "plan", "ip", false)));
        accumulator.accept(ParseOutcome.success(entry(101, "find", "db.frequent", 1L, null, 1L, "same", "plan", "ip", false)));
        JsonNode stats = json(accumulator.finish()).path("patternStats");
        assertThat(stats.size()).isEqualTo(50);
        assertThat(stats.path(0).path("namespace").asText()).isEqualTo("db.frequent");
        assertThat(StreamSupport.stream(stats.spliterator(), false)
                .filter(row -> row.path("slowestQuery").isObject()).count()).isEqualTo(50);
    }

    @Test
    void retainsPatternSampleOutsideTheGlobalTopFiveThousand() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(5_000);
        accumulator.accept(ParseOutcome.success(entry(1, "find", "db.frequent", 10L, null, 1L, "p", "plan", "ip", false)));
        accumulator.accept(ParseOutcome.success(entry(2, "find", "db.frequent", 20L, null, 1L, "p", "plan", "ip", false)));
        for (int index = 0; index < 5_000; index++) {
            accumulator.accept(ParseOutcome.success(entry(index + 3, "find", "db.slower", 1_000L + index,
                    null, 1L, "p", "plan", "ip", false)));
        }

        assertThat(accumulator.topSlowQueries()).hasSize(5_000);
        assertThat(accumulator.topSlowQueries()).extracting(SlowQueryRecord::queryId).doesNotContain("0-1", "0-2");
        JsonNode sample = StreamSupport.stream(json(accumulator.finish()).path("patternStats").spliterator(), false)
                .filter(row -> row.path("namespace").asText().equals("db.frequent"))
                .findFirst().orElseThrow().path("slowestQuery");
        assertThat(sample.path("queryId").asText()).isEqualTo("0-2");
        assertThat(sample.path("durationMillis").asLong()).isEqualTo(20);
        assertThat(sample.path("rawLine").asText()).isEqualTo("raw-2");
    }

    @Test
    void choosesPatternSampleByDurationThenEarlierTimeFileAndLine() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(1);
        accumulator.accept(ParseOutcome.success(sampleEntry(100, 9_000, 9, 9)));
        accumulator.accept(ParseOutcome.success(sampleEntry(200, 10_000, 9, 9)));
        assertPatternSample(accumulator, "9-9", 10_000);
        accumulator.accept(ParseOutcome.success(sampleEntry(200, 8_000, 9, 9)));
        assertPatternSample(accumulator, "9-9", 8_000);
        accumulator.accept(ParseOutcome.success(sampleEntry(200, 8_000, 8, 9)));
        assertPatternSample(accumulator, "8-9", 8_000);
        accumulator.accept(ParseOutcome.success(sampleEntry(200, 8_000, 8, 8)));
        assertPatternSample(accumulator, "8-8", 8_000);
        accumulator.accept(ParseOutcome.success(sampleEntry(100, 1_000, 1, 1)));
        assertPatternSample(accumulator, "8-8", 8_000);
    }

    private void assertPatternSample(AnalysisAccumulator accumulator, String queryId, long timestamp) {
        JsonNode sample = json(accumulator.finish()).path("patternStats").path(0).path("slowestQuery");
        assertThat(sample.path("queryId").asText()).isEqualTo(queryId);
        assertThat(sample.path("timestampEpochMillis").asLong()).isEqualTo(timestamp);
        assertThat(sample.path("durationMillis").asLong()).isEqualTo(200);
    }

    private ParsedLogEntry sampleEntry(long duration, long timestamp, int file, long line) {
        return new ParsedLogEntry(line, file, timestamp, "I", "COMMAND", 51803, "conn", "Slow query",
                "db.a", "find", duration, null, 1L, "IXSCAN", "ip", "p", "raw-" + file + "-" + line,
                Map.of("durationMillis", duration), true, false);
    }

    @Test
    void ranksIpAndNamespaceByResponseBytesAndKeepsFullChartTotals() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(2);
        for (int index = 0; index < 21; index++) {
            accumulator.accept(ParseOutcome.success(entry(index + 1, "find", "db." + index, 1_000L - index,
                    null, (long) index, "pattern", "plan-" + index, "ip-" + index, false)));
        }
        accumulator.accept(ParseOutcome.success(entry(30, "find", "db.tie", 1L, null, 10L, "pattern", "plan-0", "ip-0", false)));
        accumulator.accept(ParseOutcome.success(entry(31, "find", "db.tie", 1L, null, 10L, "pattern", "plan-0", "ip-0", false)));
        AnalysisSummary summary = accumulator.finish();
        assertThat(summary.namespaces()).hasSize(20);
        assertThat(summary.namespaces().keySet()).startsWith("db.tie", "db.20");
        assertThat(summary.remotes()).hasSize(20);
        assertThat(summary.remotes().keySet()).startsWith("ip-0", "ip-20");
        assertThat(summary.plans()).hasSize(21);
        JsonNode responses = json(summary).path("namespaceResponseBytes");
        assertThat(responses.size()).isEqualTo(22);
        assertThat(StreamSupport.stream(responses.spliterator(), false).mapToLong(JsonNode::asLong).sum()).isEqualTo(230);
    }

    @Test
    void bucketsCpuPercentIncludingZeroAndOverOneHundredButExcludesMissingAndZeroDuration() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(2);
        long[] cpuNanos = {0, 900_000, 10_000_000, 10_900_000, 11_000_000, 110_000_000};
        for (int index = 0; index < cpuNanos.length; index++) {
            accumulator.accept(ParseOutcome.success(entry(index + 1, "find", "db.a", 100L, cpuNanos[index], 1L, "p", "plan", "ip", false)));
        }
        accumulator.accept(ParseOutcome.success(entry(10, "find", "db.a", 100L, null, 1L, "p", "plan", "ip", false)));
        accumulator.accept(ParseOutcome.success(entry(11, "find", "db.a", 0L, 1L, 1L, "p", "plan", "ip", false)));
        accumulator.accept(ParseOutcome.success(entry(12, "remove", "db.a", 100L, 50_000_000L, 1L, "p", "plan", "ip", false)));
        JsonNode buckets = json(accumulator.finish()).path("cpuByOperationBuckets");
        assertThat(buckets.path("find").toString()).isEqualTo("{\"0\":2,\"10\":2,\"20\":1,\"110\":1}");
        assertThat(buckets.path("delete").path("50").asLong()).isEqualTo(1);
        assertThat(buckets.path("insert").size()).isZero();
        assertThat(buckets.path("update").size()).isZero();
    }

    @Test
    void averagesConnectionSamplesByUtcHourIncludingZeroWithoutFillingMissingHours() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(2);
        QueryPatternNormalizer normalizer = new QueryPatternNormalizer();
        CompositeLogParser parser = new CompositeLogParser(new StructuredLogParser(normalizer), new LegacyLogParser(normalizer));
        accumulator.accept(parser.parse("{\"t\":{\"$date\":\"2024-06-01T10:10:00Z\"},\"c\":\"NETWORK\",\"msg\":\"Connection accepted\",\"attr\":{\"connectionCount\":4}}", 1, 0));
        accumulator.accept(parser.parse("2024-06-01T18:20:00.000+0800 I NETWORK [conn1] end connection 127.0.0.1:12 (0 connections now open)", 2, 0));
        accumulator.accept(parser.parse("2024-06-01T12:30:00.000+0000 I NETWORK [listener] connection accepted from 127.0.0.1:12 #2 (8 connections now open)", 3, 0));
        accumulator.accept(parser.parse("{\"t\":{\"$date\":\"2024-06-01T08:00:00Z\"},\"msg\":\"Startup\"}", 4, 0));
        accumulator.accept(parser.parse("{\"t\":{\"$date\":\"2024-06-01T14:00:00Z\"},\"msg\":\"Shutdown\"}", 5, 0));
        accumulator.accept(ParseOutcome.failed("INVALID", "bad"));
        JsonNode summary = json(accumulator.finish());
        JsonNode averages = summary.path("averageConnections");
        assertThat(averages.size()).isEqualTo(2);
        assertThat(averages.path(0).path("timestampEpochMillis").asLong()).isEqualTo(java.time.Instant.parse("2024-06-01T10:00:00Z").toEpochMilli());
        assertThat(averages.path(0).path("sampleCount").asLong()).isEqualTo(2);
        assertThat(averages.path(0).path("averageConnections").asDouble()).isEqualTo(2);
        assertThat(averages.path(1).path("averageConnections").asDouble()).isEqualTo(8);
        assertThat(summary.path("logStartEpochMillis").asLong()).isEqualTo(java.time.Instant.parse("2024-06-01T08:00:00Z").toEpochMilli());
        assertThat(summary.path("logEndEpochMillis").asLong()).isEqualTo(java.time.Instant.parse("2024-06-01T14:00:00Z").toEpochMilli());
        JsonNode empty = json(new AnalysisAccumulator(2).finish());
        assertThat(empty.path("logStartEpochMillis").isNull()).isTrue();
        assertThat(empty.path("logEndEpochMillis").isNull()).isTrue();
        assertThat(empty.path("averageConnections").isEmpty()).isTrue();
        assertThat(empty.path("cpuAvailable").asBoolean()).isFalse();
    }

    private JsonNode json(AnalysisSummary summary) {
        return new ObjectMapper().valueToTree(summary);
    }

    @Test
    void aggregatesEverySlowQueryWhileLimitingOnlyDetails() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(2);

        accumulator.accept(ParseOutcome.success(entry(1, "find", "db.a", 100L, 10L, 20L, "p1", "IXSCAN", "10.0.0.1", false)));
        accumulator.accept(ParseOutcome.partial(entry(2, "find", "db.a", 200L, 20L, 30L, "p1", "IXSCAN", "10.0.0.1", false), "PARTIAL", "partial"));
        accumulator.accept(ParseOutcome.success(entry(3, "update", "db.b", 500L, null, 40L, "p2", "COLLSCAN", "10.0.0.2", false)));
        accumulator.accept(ParseOutcome.success(entry(4, "network", null, null, null, null, "{}", null, null, true)));
        accumulator.accept(ParseOutcome.skipped("EMPTY_LINE", "empty"));
        accumulator.accept(ParseOutcome.failed("INVALID", "bad"));

        AnalysisSummary summary = accumulator.finish();

        assertThat(summary.totalLines()).isEqualTo(6);
        assertThat(summary.successLines()).isEqualTo(3);
        assertThat(summary.partialLines()).isEqualTo(1);
        assertThat(summary.skippedLines()).isEqualTo(1);
        assertThat(summary.failedLines()).isEqualTo(1);
        assertThat(summary.parseErrors()).containsEntry("PARTIAL", 1L).containsEntry("INVALID", 1L).containsEntry("EMPTY_LINE", 1L);
        assertThat(summary.slowQueryCount()).isEqualTo(3);
        assertThat(summary.totalSlowDurationMillis()).isEqualTo(800);
        assertThat(summary.heartbeatFailures()).isEqualTo(1);
        assertThat(summary.cpuAvailable()).isTrue();
        assertThat(summary.operations().get("find").count()).isEqualTo(2);
        assertThat(summary.operations().get("find").totalDurationMillis()).isEqualTo(300);
        assertThat(summary.namespaces().get("db.a").totalResponseBytes()).isEqualTo(50);
        assertThat(summary.patterns().get("p1").count()).isEqualTo(2);
        assertThat(summary.plans().get("IXSCAN").count()).isEqualTo(2);
        assertThat(summary.remotes().get("10.0.0.1").count()).isEqualTo(2);
        assertThat(summary.cpuByOperationNamespace().get("find|db.a").totalCpuNanos()).isEqualTo(30);
        assertThat(summary.durationDistribution()).extracting(DurationBucketStat::count)
                .containsExactly(0L, 2L, 1L, 0L, 0L, 0L, 0L, 0L);
        assertThat(accumulator.topSlowQueries()).extracting(SlowQueryRecord::durationMillis)
                .containsExactly(500L, 200L);
    }

    private ParsedLogEntry entry(
            long line,
            String operation,
            String namespace,
            Long duration,
            Long cpu,
            Long responseLength,
            String pattern,
            String plan,
            String remote,
            boolean heartbeat
    ) {
        return new ParsedLogEntry(
                line, 0, 1_000 + line, "I", "COMMAND", null, "conn", "message",
                namespace, operation, duration, cpu, responseLength, plan, remote, pattern,
                "raw-" + line, Map.of(), duration != null, heartbeat
        );
    }
}
