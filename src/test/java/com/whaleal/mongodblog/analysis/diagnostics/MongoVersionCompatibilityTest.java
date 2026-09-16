package com.whaleal.mongodblog.analysis.diagnostics;

import com.whaleal.mongodblog.parser.QueryPatternNormalizer;
import com.whaleal.mongodblog.parser.StructuredLogParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MongoVersionCompatibilityTest {
    private final StructuredLogParser parser = new StructuredLogParser(new QueryPatternNormalizer());

    @Test
    void acceptsFieldsAddedAcrossMongoDb44Through81ByCapability() {
        List<String> versionFields = List.of(
                "\"queryHash\":\"OLD\",\"planCacheKey\":\"KEY44\"",
                "\"remoteOpWaitMillis\":12,\"authorization\":{\"userCacheWaitTimeMicros\":3000}",
                "\"queryFramework\":\"sbe\",\"cpuNanos\":9000",
                "\"totalOplogSlotDurationMicros\":4000",
                "\"planCacheShapeHash\":\"NEW\",\"queryShapeHash\":\"SHAPE\",\"workingMillis\":90,\"queues\":{\"execution\":{\"totalTimeQueuedMicros\":5000}}",
                "\"sortSpills\":2,\"sortSpilledBytes\":8192,\"sortSpilledRecords\":40"
        );
        DiagnosticAccumulator accumulator = new DiagnosticAccumulator();
        for (int index = 0; index < versionFields.size(); index++) {
            accumulator.accept(parser.parse(slowQuery(versionFields.get(index)), index + 1, 0));
        }

        LogDiagnostics.SlowQueryDiagnostics result = accumulator.finish().slowQueries();

        assertThat(result.total()).isEqualTo(6);
        assertThat(result.fieldCoverage()).containsKeys(
                "queryHash", "planCacheKey", "remoteOpWaitMillis", "authorization.userCacheWaitTimeMicros",
                "queryFramework", "cpuNanos", "totalOplogSlotDurationMicros", "planCacheShapeHash",
                "queryShapeHash", "workingMillis", "queues", "spilledBytes", "spilledRecords");
        assertThat(result.remoteOpWaitCount()).isEqualTo(1);
        assertThat(result.authorizationWaitCount()).isEqualTo(1);
        assertThat(result.queueWaitCount()).isEqualTo(1);
        assertThat(result.oplogSlotWaitCount()).isEqualTo(1);
        assertThat(result.spillCount()).isEqualTo(1);
        assertThat(result.insights()).extracting(LogDiagnostics.SlowQueryInsight::reasons)
                .anySatisfy(reasons -> assertThat(reasons).contains("等待分片响应"))
                .anySatisfy(reasons -> assertThat(reasons).contains("执行队列等待"))
                .anySatisfy(reasons -> assertThat(reasons).contains("查询执行落盘"));
    }

    @Test
    void capturesOptionalStructuredEnvelopeWithoutRequiringIt() {
        String line = "{\"t\":{\"$date\":\"2024-01-01T00:00:00Z\"},\"s\":\"W\",\"c\":\"CONTROL\",\"id\":1,\"ctx\":\"main\",\"svc\":\"R\",\"msg\":\"warning\",\"tags\":[\"startupWarnings\"],\"truncated\":{\"attr\":{}},\"attr\":{}}";
        var entry = parser.parse(line, 1, 0).entry().orElseThrow();
        assertThat(entry.envelopeMetadata().service()).isEqualTo("R");
        assertThat(entry.envelopeMetadata().tags()).containsExactly("startupWarnings");
        assertThat(entry.envelopeMetadata().truncated()).isTrue();
    }

    private String slowQuery(String extraFields) {
        return "{\"t\":{\"$date\":\"2024-01-01T00:00:00Z\"},\"s\":\"I\",\"c\":\"COMMAND\",\"id\":51803,\"ctx\":\"conn\",\"msg\":\"Slow query\",\"attr\":{\"type\":\"command\",\"ns\":\"db.items\",\"command\":{\"find\":\"items\",\"filter\":{\"state\":\"open\"}},\"durationMillis\":100," + extraFields + "}}";
    }
}
