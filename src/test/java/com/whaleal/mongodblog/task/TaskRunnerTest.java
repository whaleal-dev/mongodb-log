package com.whaleal.mongodblog.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.parser.CompositeLogParser;
import com.whaleal.mongodblog.parser.LegacyLogParser;
import com.whaleal.mongodblog.parser.QueryPatternNormalizer;
import com.whaleal.mongodblog.parser.StructuredLogParser;
import com.whaleal.mongodblog.storage.FileTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRunnerTest {

    @TempDir
    Path dataDir;

    @Test
    void streamsPlainAndGzipFilesAndCleansWorkDirectory() throws Exception {
        FileTaskRepository repository = repository();
        Path work = Files.createDirectories(dataDir.resolve("work/task-ok"));
        String structured = Files.readString(Path.of("src/test/resources/fixtures/structured.log"));
        String legacy = Files.readString(Path.of("src/test/resources/fixtures/legacy.log")).lines().findFirst().orElseThrow();
        Path plain = work.resolve("0000-structured.log");
        Path gzip = work.resolve("0001-legacy.log.gz");
        Files.writeString(plain, structured.stripTrailing() + System.lineSeparator(), StandardCharsets.UTF_8);
        writeGzip(gzip, legacy + System.lineSeparator());
        AnalysisTask task = task("task-ok", List.of(
                new TaskInputFile("structured.log", plain.getFileName().toString(), Files.size(plain)),
                new TaskInputFile("legacy.log.gz", gzip.getFileName().toString(), Files.size(gzip))
        ));
        repository.saveTask(task);

        runner(repository).run(task.id());

        AnalysisTask completed = repository.findTask(task.id()).orElseThrow();
        AnalysisSummary summary = repository.readSummary(task.id());
        LogDiagnostics diagnostics = repository.readDiagnostics(task.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.processedLines()).isEqualTo(2);
        assertThat(completed.logStartEpochMillis()).isEqualTo(1717236930123L);
        assertThat(completed.logEndEpochMillis()).isEqualTo(java.time.Instant.parse("2025-03-10T05:28:38.624Z").toEpochMilli());
        assertThat(summary.logStartEpochMillis()).isEqualTo(completed.logStartEpochMillis());
        assertThat(summary.logEndEpochMillis()).isEqualTo(completed.logEndEpochMillis());
        assertThat(summary.patternStats()).hasSize(2);
        assertThat(summary.patternStats()).allSatisfy(stat -> assertThat(stat.slowestQueryId()).isNotNull());
        assertThat(summary.slowQueryCount()).isEqualTo(2);
        assertThat(repository.readSlowQueries(task.id()))
                .extracting(record -> record.durationMillis())
                .containsExactly(742L, 325L);
        assertThat(summary.durationDistribution()).extracting(stat -> stat.count())
                .containsExactly(0L, 1L, 1L, 0L, 0L, 0L, 0L, 0L);
        assertThat(diagnostics.totalParsedLines()).isEqualTo(2);
        assertThat(diagnostics.dataQuality().structuredLines()).isEqualTo(1);
        assertThat(diagnostics.dataQuality().legacyLines()).isEqualTo(1);
        assertThat(Files.readString(dataDir.resolve("tasks/task-ok/summary.json"))).doesNotContain("diagnostics");
        assertThat(work).doesNotExist();
    }

    @Test
    void marksDamagedGzipAsFailedAndStillCleansWorkDirectory() throws Exception {
        FileTaskRepository repository = repository();
        Path work = Files.createDirectories(dataDir.resolve("work/task-bad"));
        Path gzip = work.resolve("0000-bad.log.gz");
        Files.writeString(gzip, "not-gzip", StandardCharsets.UTF_8);
        AnalysisTask task = task("task-bad", List.of(
                new TaskInputFile("bad.log.gz", gzip.getFileName().toString(), Files.size(gzip))
        ));
        repository.saveTask(task);

        runner(repository).run(task.id());

        AnalysisTask failed = repository.findTask(task.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.errorMessage()).contains("GZIP");
        assertThat(work).doesNotExist();
    }

    @Test
    void publishesTerminalStatusOnlyAfterUploadCleanupToAvoidDeleteRaces() throws Exception {
        AtomicBoolean workExistedAtCompletion = new AtomicBoolean();
        FileTaskRepository repository = new FileTaskRepository(dataDir, new ObjectMapper()) {
            @Override
            public synchronized void saveTask(AnalysisTask task) {
                if (task.status() == TaskStatus.COMPLETED) {
                    workExistedAtCompletion.set(Files.exists(dataDir.resolve("work").resolve(task.id())));
                }
                super.saveTask(task);
            }
        };
        Path work = Files.createDirectories(dataDir.resolve("work/cleanup-first"));
        Path input = Files.writeString(work.resolve("mongo.log"), Files.readString(Path.of("src/test/resources/fixtures/structured.log")));
        repository.saveTask(task("cleanup-first", List.of(new TaskInputFile("mongo.log", "mongo.log", Files.size(input)))));
        runner(repository).run("cleanup-first");
        assertThat(repository.findTask("cleanup-first").orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(workExistedAtCompletion).isFalse();
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void reportsUploadCleanupFailureInsteadOfSilentlyCompleting() throws Exception {
        FileTaskRepository repository = repository();
        Path work = Files.createDirectories(dataDir.resolve("work/cleanup-failure"));
        Path input = Files.writeString(work.resolve("mongo.log"), Files.readString(Path.of("src/test/resources/fixtures/structured.log")));
        repository.saveTask(task("cleanup-failure", List.of(new TaskInputFile("mongo.log", "mongo.log", Files.size(input)))));
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(work);
        try {
            Files.setPosixFilePermissions(work, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
            runner(repository).run("cleanup-failure");
            AnalysisTask failed = repository.findTask("cleanup-failure").orElseThrow();
            assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
            assertThat(failed.errorMessage()).contains("上传副本清理失败");
            assertThat(input).exists();
        } finally {
            Files.setPosixFilePermissions(work, permissions);
        }
    }

    @Test
    void continuesAfterMalformedNumericMetricsAndCountsTheirErrors() throws Exception {
        FileTaskRepository repository = repository();
        Path work = Files.createDirectories(dataDir.resolve("work/numeric-errors"));
        String malformed = "2025-03-10T14:14:17.729+0800 I COMMAND [conn1] command db.items command: find { find: \"items\", filter: {} } 99999999999999999999999999999ms";
        Path input = Files.writeString(work.resolve("mongo.log"), malformed + "\n" + Files.readString(Path.of("src/test/resources/fixtures/structured.log")));
        repository.saveTask(task("numeric-errors", List.of(new TaskInputFile("mongo.log", "mongo.log", Files.size(input)))));
        runner(repository).run("numeric-errors");
        assertThat(repository.findTask("numeric-errors").orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        AnalysisSummary summary = repository.readSummary("numeric-errors");
        assertThat(summary.totalLines()).isEqualTo(2);
        assertThat(summary.partialLines()).isEqualTo(1);
        assertThat(summary.parseErrors()).containsEntry("INVALID_SLOW_QUERY_DURATION", 1L);
        assertThat(summary.slowQueryCount()).isEqualTo(1);
    }

    private TaskRunner runner(FileTaskRepository repository) {
        QueryPatternNormalizer normalizer = new QueryPatternNormalizer();
        return new TaskRunner(
                dataDir,
                repository,
                new CompositeLogParser(
                        new StructuredLogParser(normalizer),
                        new LegacyLogParser(normalizer)
                )
        );
    }

    private FileTaskRepository repository() {
        return new FileTaskRepository(dataDir, new ObjectMapper().findAndRegisterModules());
    }

    private AnalysisTask task(String id, List<TaskInputFile> files) {
        long totalBytes = files.stream().mapToLong(TaskInputFile::sizeBytes).sum();
        return new AnalysisTask(id, id, TaskStatus.QUEUED, 1_000, null, null, files, totalBytes, 0, 0, null, null, null);
    }

    private void writeGzip(Path path, String text) throws IOException {
        try (GZIPOutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
