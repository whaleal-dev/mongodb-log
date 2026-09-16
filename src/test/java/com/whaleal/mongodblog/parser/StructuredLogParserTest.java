package com.whaleal.mongodblog.parser;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import com.whaleal.mongodblog.analysis.AnalysisAccumulator;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLogParserTest {

    private static final String SLOW_QUERY = """
            {"t":{"$date":"2024-06-01T10:15:30.123+00:00"},"s":"I","c":"COMMAND","id":51803,"ctx":"conn12","msg":"Slow query","attr":{"type":"command","ns":"sales.orders","command":{"find":"orders","filter":{"status":"OPEN","amount":{"$gt":100}},"$db":"sales"},"planSummary":"IXSCAN { status: 1 }","durationMillis":742,"cpuNanos":2100000,"reslen":5120,"remote":"10.0.0.8:41712"}}
            """.trim();

    private final StructuredLogParser parser = new StructuredLogParser(new QueryPatternNormalizer());

    @Test
    void parsesStructuredSlowQueryFields() {
        ParseOutcome outcome = parser.parse(SLOW_QUERY, 27, 2);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        ParsedLogEntry entry = outcome.entry().orElseThrow();
        assertThat(entry.timestampEpochMillis()).isEqualTo(Instant.parse("2024-06-01T10:15:30.123Z").toEpochMilli());
        assertThat(entry.severity()).isEqualTo("I");
        assertThat(entry.component()).isEqualTo("COMMAND");
        assertThat(entry.messageId()).isEqualTo(51803);
        assertThat(entry.context()).isEqualTo("conn12");
        assertThat(entry.message()).isEqualTo("Slow query");
        assertThat(entry.namespace()).isEqualTo("sales.orders");
        assertThat(entry.operation()).isEqualTo("find");
        assertThat(entry.durationMillis()).isEqualTo(742L);
        assertThat(entry.cpuNanos()).isEqualTo(2_100_000L);
        assertThat(entry.responseLength()).isEqualTo(5_120L);
        assertThat(entry.planSummary()).isEqualTo("IXSCAN { status: 1 }");
        assertThat(entry.remote()).isEqualTo("10.0.0.8");
        assertThat(entry.queryPattern()).isEqualTo("{\"amount\":{\"$gt\":\"?\"},\"status\":\"?\"}");
        assertThat(entry.slowQuery()).isTrue();
        assertThat(entry.fileIndex()).isEqualTo(2);
        assertThat(entry.lineNumber()).isEqualTo(27);
        assertThat(entry.rawLine()).isEqualTo(SLOW_QUERY);
    }

    @Test
    void acceptsBomAndWhitespaceWithoutChangingRawLine() {
        String line = "\uFEFF  " + SLOW_QUERY + "  ";

        ParseOutcome outcome = parser.parse(line, 1, 0);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(outcome.entry().orElseThrow().rawLine()).isEqualTo(line);
    }

    @Test
    void acceptsMissingOptionalSlowQueryFields() {
        String line = """
                {"t":{"$date":"2024-06-01T10:15:30Z"},"s":"I","c":"COMMAND","id":1,"ctx":"conn1","msg":"Slow query","attr":{"ns":"db.items","durationMillis":120}}
                """.trim();

        ParseOutcome outcome = parser.parse(line, 3, 0);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        ParsedLogEntry entry = outcome.entry().orElseThrow();
        assertThat(entry.operation()).isEqualTo("command");
        assertThat(entry.queryPattern()).isEqualTo("{}");
        assertThat(entry.cpuNanos()).isNull();
        assertThat(entry.responseLength()).isNull();
    }

    @Test
    void doesNotTreatUnrelatedDurationAsSlowQuery() {
        String line = """
                {"t":{"$date":"2026-08-18T08:27:16.091+00:00"},"s":"I","c":"NETWORK","id":6006301,"ctx":"ReplicaSetMonitor-TaskExecutor","msg":"Replica set primary server change detected","attr":{"replicaSet":"jmidb","topologyType":"ReplicaSetNoPrimary","primary":"Unknown","durationMillis":1194975914}}
                """.trim();

        ParseOutcome outcome = parser.parse(line, 808714, 1);

        assertThat(outcome.status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(outcome.entry().orElseThrow().slowQuery()).isFalse();
    }

    @Test
    void reportsDamagedStructuredJson() {
        ParseOutcome outcome = parser.parse("{\"t\": {\"$date\": \"2024-06-01T10:15:30Z\"}", 4, 0);

        assertThat(outcome.status()).isEqualTo(ParseStatus.FAILED);
        assertThat(outcome.entry()).isEmpty();
        assertThat(outcome.errorCode()).isEqualTo("INVALID_STRUCTURED_JSON");
        assertThat(outcome.errorMessage()).isNotBlank();
    }

    @Test
    void identifiesFindAndModifyFromTheCommandNameInsteadOfItsUpdateArgument() {
        ParseOutcome result = parser.parse(slowAttributes("\"command\":{\"findAndModify\":\"items\",\"query\":{\"state\":\"open\"},\"update\":{\"$set\":{\"state\":\"closed\"}}},\"durationMillis\":100"), 1, 0);
        assertThat(result.entry().orElseThrow().operation()).isEqualTo("findAndModify");
        assertThat(result.entry().orElseThrow().queryPattern()).isEqualTo("{\"state\":\"?\"}");
    }

    @Test
    void preservesPredicatesOfEveryUpdateStatementAndOriginatingAggregationPipeline() {
        ParsedLogEntry update = parser.parse(slowAttributes("\"command\":{\"update\":\"items\",\"updates\":[{\"q\":{\"a\":1},\"u\":{\"$set\":{\"x\":1}}},{\"q\":{\"b\":2},\"u\":{\"$set\":{\"x\":2}}}]},\"durationMillis\":100"), 1, 0).entry().orElseThrow();
        assertThat(update.operation()).isEqualTo("update");
        assertThat(update.queryPattern()).isEqualTo("[{\"a\":\"?\"},{\"b\":\"?\"}]");
        ParsedLogEntry getMore = parser.parse(slowAttributes("\"command\":{\"getMore\":99,\"collection\":\"items\"},\"originatingCommand\":{\"aggregate\":\"items\",\"pipeline\":[{\"$match\":{\"state\":\"A\"}}]},\"durationMillis\":100"), 2, 0).entry().orElseThrow();
        assertThat(getMore.operation()).isEqualTo("getMore");
        assertThat(getMore.queryPattern()).isEqualTo("[{\"$match\":{\"state\":\"?\"}}]");
    }

    @Test
    void classifiesMissingOrInvalidSlowDurationAndDoesNotPolluteAggregates() {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(5_000);
        for (String value : List.of("null", "-1", "1.5", "\"bad\"", "{\"$numberDouble\":\"NaN\"}", "\"9223372036854775808\"")) {
            ParseOutcome result = parser.parse(slowAttributes("\"durationMillis\":" + value), 1, 0);
            assertThat(result.status()).as(value).isEqualTo(ParseStatus.PARTIAL);
            assertThat(result.entry().orElseThrow().durationMillis()).isNull();
            assertThat(result.errorCode()).isIn("MISSING_SLOW_QUERY_DURATION", "INVALID_SLOW_QUERY_DURATION");
            accumulator.accept(result);
        }
        ParseOutcome missing = parser.parse(slowAttributes("\"command\":{\"find\":\"items\"}"), 2, 0);
        assertThat(missing.errorCode()).isEqualTo("MISSING_SLOW_QUERY_DURATION");
        accumulator.accept(missing);
        accumulator.accept(parser.parse(slowAttributes("\"durationMillis\":0"), 3, 0));
        assertThat(accumulator.finish().partialLines()).isEqualTo(7);
        assertThat(accumulator.finish().slowQueryCount()).isEqualTo(1);
        assertThat(accumulator.finish().totalSlowDurationMillis()).isZero();
        assertThat(accumulator.finish().durationDistribution().get(0).count()).isEqualTo(1);
        assertThat(accumulator.finish().logStartEpochMillis()).isEqualTo(Instant.parse("2024-06-01T10:15:30Z").toEpochMilli());
        ParseOutcome overflow = parser.parse(slowAttributes("\"durationMillis\":9223372036854775808"), 4, 0);
        assertThat(overflow.status()).isEqualTo(ParseStatus.FAILED);
        assertThat(overflow.errorCode()).isEqualTo("INVALID_STRUCTURED_JSON");
    }

    @Test
    void excludesInvalidCpuAndResponseMetricsWhilePreservingValidDuration() {
        for (String metric : List.of("\"cpuNanos\":-1", "\"reslen\":-1", "\"cpuNanos\":1.5", "\"reslen\":\"bad\"")) {
            ParseOutcome result = parser.parse(slowAttributes("\"durationMillis\":100," + metric), 1, 0);
            assertThat(result.status()).as(metric).isEqualTo(ParseStatus.PARTIAL);
            assertThat(result.errorCode()).isEqualTo("INVALID_SLOW_QUERY_METRICS");
            assertThat(result.entry().orElseThrow().cpuNanos()).isNull();
            assertThat(result.entry().orElseThrow().responseLength()).isNull();
            AnalysisAccumulator accumulator = new AnalysisAccumulator(1);
            accumulator.accept(result);
            assertThat(accumulator.finish().slowQueryCount()).isEqualTo(1);
            assertThat(accumulator.finish().cpuAvailable()).isFalse();
            assertThat(accumulator.finish().namespaces().get("db.items").totalResponseBytes()).isZero();
        }
    }

    private String slowAttributes(String fields) {
        return "{\"t\":{\"$date\":\"2024-06-01T10:15:30Z\"},\"s\":\"I\",\"c\":\"COMMAND\",\"msg\":\"Slow query\",\"attr\":{\"ns\":\"db.items\"," + fields + "}}";
    }
}
