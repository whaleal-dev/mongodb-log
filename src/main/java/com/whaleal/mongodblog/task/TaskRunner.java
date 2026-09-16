package com.whaleal.mongodblog.task;

import com.whaleal.mongodblog.analysis.AnalysisAccumulator;
import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.diagnostics.DiagnosticAccumulator;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.parser.LogParser;
import com.whaleal.mongodblog.parser.ParseOutcome;
import com.whaleal.mongodblog.storage.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

@Component
public class TaskRunner {
    private static final int TOP_QUERY_LIMIT = 5_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRunner.class);

    private final Path dataDirectory;
    private final TaskRepository repository;
    private final LogParser parser;

    @Autowired
    public TaskRunner(
            @Value("${mongodblog.data-dir}") String dataDirectory,
            TaskRepository repository,
            LogParser parser
    ) {
        this(Path.of(dataDirectory), repository, parser);
    }

    public TaskRunner(Path dataDirectory, TaskRepository repository, LogParser parser) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.repository = repository;
        this.parser = parser;
    }

    public void run(String taskId) {
        AnalysisTask task = repository.findTask(taskId)
                .orElseThrow(() -> new NoSuchElementException("任务不存在：" + taskId));
        AnalysisTask running = task.running(System.currentTimeMillis());
        repository.saveTask(running);
        AnalysisAccumulator accumulator = new AnalysisAccumulator(TOP_QUERY_LIMIT);
        DiagnosticAccumulator diagnosticsAccumulator = new DiagnosticAccumulator();
        long processedBytes = 0;
        long processedLines = 0;
        Path workDirectory = dataDirectory.resolve("work").resolve(taskId);
        AnalysisTask terminal = running;

        try {
            for (int fileIndex = 0; fileIndex < running.files().size(); fileIndex++) {
                TaskInputFile file = running.files().get(fileIndex);
                Path path = workDirectory.resolve(file.storedName()).normalize();
                if (!path.startsWith(workDirectory)) {
                    throw new IOException("工作文件路径越界");
                }
                FileProgress progress = processFile(path, file.originalName(), fileIndex, accumulator,
                        diagnosticsAccumulator, processedBytes, processedLines, running);
                processedBytes = progress.processedBytes();
                processedLines = progress.processedLines();
                running = running.progress(processedBytes, processedLines);
                repository.saveTask(running);
            }
            AnalysisSummary summary = accumulator.finish();
            repository.saveResult(taskId, summary, accumulator.topSlowQueries());
            LogDiagnostics diagnostics = diagnosticsAccumulator.finish();
            try {
                repository.saveDiagnostics(taskId, diagnostics);
            } catch (RuntimeException diagnosticsFailure) {
                LOGGER.warn("任务 {} 的运行诊断保存失败，慢查询分析结果仍然可用", taskId, diagnosticsFailure);
            }
            terminal = running.completed(System.currentTimeMillis(), running.totalBytes(), processedLines,
                    summary.logStartEpochMillis(), summary.logEndEpochMillis());
        } catch (ZipException e) {
            terminal = running.progress(processedBytes, processedLines)
                    .failed(System.currentTimeMillis(), "GZIP 文件损坏：" + safeMessage(e));
        } catch (Exception e) {
            terminal = running.progress(processedBytes, processedLines)
                    .failed(System.currentTimeMillis(), "分析失败：" + safeMessage(e));
        } finally {
            try {
                deleteWorkDirectory(workDirectory);
            } catch (IOException e) {
                String prefix = terminal.errorMessage() == null ? "" : terminal.errorMessage() + "；";
                terminal = terminal.failed(System.currentTimeMillis(), prefix + "上传副本清理失败：" + safeMessage(e));
            }
        }
        repository.saveTask(terminal);
    }

    private FileProgress processFile(
            Path path,
            String originalName,
            int fileIndex,
            AnalysisAccumulator accumulator,
            DiagnosticAccumulator diagnosticsAccumulator,
            long bytesBefore,
            long linesBefore,
            AnalysisTask running
    ) throws IOException {
        try (CountingInputStream counting = new CountingInputStream(Files.newInputStream(path));
             InputStream decoded = isGzip(originalName) ? new GZIPInputStream(counting) : counting;
             BufferedReader reader = new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
            long lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                ParseOutcome outcome = parser.parse(line, lineNumber, fileIndex);
                accumulator.accept(outcome);
                diagnosticsAccumulator.accept(outcome);
                if (lineNumber % 1_000 == 0) {
                    repository.saveTask(running.progress(bytesBefore + counting.count(), linesBefore + lineNumber));
                }
            }
            return new FileProgress(bytesBefore + counting.count(), linesBefore + lineNumber);
        }
    }

    private boolean isGzip(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    private void deleteWorkDirectory(Path workDirectory) throws IOException {
        if (!Files.exists(workDirectory)) {
            return;
        }
        try (var paths = Files.walk(workDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private record FileProgress(long processedBytes, long processedLines) {
    }
}
