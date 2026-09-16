package com.whaleal.mongodblog.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.AnalysisAccumulator;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;
import com.whaleal.mongodblog.analysis.diagnostics.DiagnosticAccumulator;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.parser.ParseOutcome;
import com.whaleal.mongodblog.parser.ParsedLogEntry;
import com.whaleal.mongodblog.task.AnalysisTask;
import com.whaleal.mongodblog.task.TaskInputFile;
import com.whaleal.mongodblog.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileTaskRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsTaskSummaryAndJsonLinesWithoutTemporaryResidue() throws Exception {
        FileTaskRepository repository = repository();
        AnalysisTask task = task("task-1", TaskStatus.QUEUED, null);
        AnalysisSummary summary = emptySummary(2, 800);
        List<SlowQueryRecord> records = List.of(
                record("0-2", 500),
                record("0-1", 300)
        );
        LogDiagnostics diagnostics = new DiagnosticAccumulator().finish();

        repository.saveTask(task);
        repository.saveResult(task.id(), summary, records);
        repository.saveDiagnostics(task.id(), diagnostics);

        assertThat(repository.findTask(task.id())).contains(task);
        assertThat(repository.listTasks()).containsExactly(task);
        assertThat(repository.readSummary(task.id())).isEqualTo(summary);
        assertThat(repository.readSlowQueries(task.id())).containsExactlyElementsOf(records);
        assertThat(repository.readSlowQuery(task.id(), "0-1")).contains(records.get(1));
        assertThat(repository.readDiagnostics(task.id())).contains(diagnostics);
        try (var paths = Files.walk(tempDir)) {
            assertThat(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"))).isTrue();
        }
    }

    @Test
    void marksInterruptedRunningTasksAsFailedOnStartup() {
        FileTaskRepository firstProcess = repository();
        firstProcess.saveTask(task("task-running", TaskStatus.RUNNING, null));

        FileTaskRepository restartedProcess = repository();

        AnalysisTask recovered = restartedProcess.findTask("task-running").orElseThrow();
        assertThat(recovered.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(recovered.errorMessage()).isEqualTo("应用在分析过程中退出");
        assertThat(recovered.completedAtEpochMillis()).isNotNull();
    }

    @Test
    void marksAbandonedQueuedTasksFailedOnRestartSoTheyCanBeRemoved() {
        FileTaskRepository first = repository();
        first.saveTask(task("queued", TaskStatus.QUEUED, null));
        FileTaskRepository restarted = repository();
        assertThat(restarted.findTask("queued").orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        restarted.deleteTask("queued");
        assertThat(repository().findTask("queued")).isEmpty();
    }

    @Test
    void readsHistoricalTasksWithoutInventingNewStatistics() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode taskJson = mapper.valueToTree(task("historical", TaskStatus.COMPLETED, null));
        taskJson.remove(List.of("logStartEpochMillis", "logEndEpochMillis"));
        Files.writeString(tempDir.resolve("tasks-index.json"), "[" + taskJson + "]");
        Path taskDirectory = Files.createDirectories(tempDir.resolve("tasks/historical"));
        ObjectNode summaryJson = mapper.valueToTree(emptySummary(2, 800));
        summaryJson.remove(List.of("patternStats", "namespaceResponseBytes", "cpuByOperationBuckets",
                "averageConnections", "logStartEpochMillis", "logEndEpochMillis"));
        Files.writeString(taskDirectory.resolve("summary.json"), summaryJson.toString());

        FileTaskRepository repository = repository();
        AnalysisTask historical = repository.findTask("historical").orElseThrow();
        AnalysisSummary summary = repository.readSummary("historical");
        assertThat(historical.logStartEpochMillis()).isNull();
        assertThat(historical.logEndEpochMillis()).isNull();
        assertThat(summary.slowQueryCount()).isEqualTo(2);
        assertThat(summary.totalSlowDurationMillis()).isEqualTo(800);
        assertThat(summary.patternStats()).isNull();
        assertThat(summary.namespaceResponseBytes()).isNull();
        assertThat(summary.cpuByOperationBuckets()).isNull();
        assertThat(summary.averageConnections()).isNull();
        assertThat(repository.readDiagnostics("historical")).isEmpty();
    }

    @Test
    void persistsOnlyFiftyPatternSamplesSeparatelyFromGlobalDetailsAndReadsOldRows() throws Exception {
        AnalysisAccumulator accumulator = new AnalysisAccumulator(1);
        for (int index = 0; index < 52; index++) {
            accumulator.accept(ParseOutcome.success(patternEntry(index * 2 + 1, "db.%02d".formatted(index), 10L)));
            accumulator.accept(ParseOutcome.success(patternEntry(index * 2 + 2, "db.%02d".formatted(index), 20L)));
        }
        FileTaskRepository repository = repository();
        repository.saveTask(task("samples", TaskStatus.COMPLETED, null));
        repository.saveResult("samples", accumulator.finish(), accumulator.topSlowQueries());

        ObjectMapper mapper = new ObjectMapper();
        Path summaryPath = tempDir.resolve("tasks/samples/summary.json");
        ObjectNode saved = (ObjectNode) mapper.readTree(Files.readString(summaryPath));
        assertThat(saved.path("patternStats").size()).isEqualTo(50);
        assertThat(saved.path("patternStats").path(49).path("slowestQuery").path("rawLine").asText()).isEqualTo("raw-100");
        assertThat(Files.readString(summaryPath)).doesNotContain("raw-1\"", "raw-101\"", "raw-102\"", "raw-103\"", "raw-104\"");
        AnalysisSummary restored = repository.readSummary("samples");
        assertThat(mapper.writeValueAsString(restored)).isEqualTo(mapper.writeValueAsString(accumulator.finish()));
        assertThat(repository.readSlowQueries("samples")).hasSize(1);
        assertThat(repository.readSlowQuery("samples", "0-100")).isEmpty();

        for (var row : saved.path("patternStats")) {
            ((ObjectNode) row).remove("slowestQuery");
        }
        Files.writeString(summaryPath, saved.toString());
        var historical = mapper.valueToTree(repository.readSummary("samples"));
        assertThat(historical.path("patternStats").path(0).path("count").asLong()).isEqualTo(2);
        assertThat(historical.path("patternStats").path(0).path("slowestQuery").isNull()).isTrue();
    }

    private ParsedLogEntry patternEntry(long line, String namespace, long duration) {
        return new ParsedLogEntry(line, 0, 1_000 + line, "I", "COMMAND", 51803, "conn", "Slow query",
                namespace, "find", duration, null, 1L, "IXSCAN", "ip", "p", "raw-" + line,
                Map.of("durationMillis", duration), true, false);
    }

    private FileTaskRepository repository() {
        return new FileTaskRepository(tempDir, new ObjectMapper().findAndRegisterModules());
    }

    private AnalysisTask task(String id, TaskStatus status, String error) {
        return new AnalysisTask(
                id, "测试任务", status, 1_000, status == TaskStatus.QUEUED ? null : 1_100L,
                null, List.of(new TaskInputFile("mongo.log", "0000-mongo.log", 200)),
                200, 0, 0, error, null, null
        );
    }

    private AnalysisSummary emptySummary(long slowCount, long duration) {
        return new AnalysisSummary(
                slowCount, slowCount, 0, 0, 0, slowCount, duration, 0, false,
                Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), Map.of(), Map.of(), List.of(), null, null
        );
    }

    private SlowQueryRecord record(String id, long duration) {
        return new SlowQueryRecord(
                id, 0, Long.parseLong(id.substring(2)), 1_000, "find", "db.items", duration,
                null, 20L, "IXSCAN", "127.0.0.1", "{}", "raw", Map.of()
        );
    }
}
