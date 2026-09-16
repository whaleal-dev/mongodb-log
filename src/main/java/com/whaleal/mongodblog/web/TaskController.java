package com.whaleal.mongodblog.web;

import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;
import com.whaleal.mongodblog.parser.LogParser;
import com.whaleal.mongodblog.parser.ParsedLogEntry;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.report.MarkdownReportService;
import com.whaleal.mongodblog.storage.TaskRepository;
import com.whaleal.mongodblog.task.AnalysisTask;
import com.whaleal.mongodblog.task.TaskService;
import com.whaleal.mongodblog.task.TaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService taskService;
    private final TaskRepository repository;
    private final LogParser parser;
    private final MarkdownReportService reports;

    public TaskController(TaskService taskService, TaskRepository repository, LogParser parser,
                          MarkdownReportService reports) {
        this.taskService = taskService;
        this.repository = repository;
        this.parser = parser;
        this.reports = reports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisTask> create(
            @RequestParam(required = false) String name,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(taskService.create(name, files));
    }

    @GetMapping
    public List<AnalysisTask> list() {
        return taskService.list();
    }

    @GetMapping("/{taskId}")
    public AnalysisTask get(@PathVariable String taskId) {
        return taskService.get(taskId);
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable String taskId) {
        taskService.delete(taskId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{taskId}/summary")
    public AnalysisSummary summary(@PathVariable String taskId) {
        requireCompleted(taskId);
        return repository.readSummary(taskId);
    }

    @GetMapping("/{taskId}/diagnostics")
    public LogDiagnostics diagnostics(@PathVariable String taskId) {
        requireCompleted(taskId);
        return repository.readDiagnostics(taskId)
                .orElseThrow(() -> new NoSuchElementException("此历史任务未生成运行诊断，请重新上传日志"));
    }

    @GetMapping(value = "/{taskId}/report.md", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable String taskId) {
        requireCompleted(taskId);
        MarkdownReportService.Report report = reports.generate(taskId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(report.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(report.content());
    }

    @GetMapping("/{taskId}/slow-queries")
    public SlowQueryPage slowQueries(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String operation,
            @RequestParam(defaultValue = "0") long minDurationMillis,
            @RequestParam(required = false) String planSummary
    ) {
        validatePage(page, size, minDurationMillis);
        requireCompleted(taskId);
        List<SlowQueryRecord> filtered = repository.readSlowQueries(taskId).stream()
                .filter(record -> contains(record.namespace(), namespace))
                .filter(record -> contains(record.operation(), operation))
                .filter(record -> record.durationMillis() >= minDurationMillis)
                .filter(record -> contains(record.planSummary(), planSummary))
                .toList();
        int from = Math.min((page - 1) * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        return new SlowQueryPage(page, size, filtered.size(), filtered.subList(from, to));
    }

    @GetMapping("/{taskId}/slow-query-points")
    public SlowQueryScatterResponse slowQueryPoints(@PathVariable String taskId) {
        requireCompleted(taskId);
        Map<String, List<List<Object>>> grouped = new LinkedHashMap<>();
        for (SlowQueryRecord record : repository.readSlowQueries(taskId)) {
            String namespace = record.namespace() == null || record.namespace().isBlank()
                    ? "unknown"
                    : record.namespace();
            grouped.computeIfAbsent(namespace, ignored -> new java.util.ArrayList<>())
                    .add(List.of(record.timestampEpochMillis(), record.durationMillis(), record.queryId()));
        }
        return new SlowQueryScatterResponse(grouped.entrySet().stream()
                .map(entry -> new SlowQueryScatterResponse.Series(entry.getKey(), List.copyOf(entry.getValue())))
                .toList());
    }

    @GetMapping("/{taskId}/slow-queries/{queryId}")
    public SlowQueryDetail slowQuery(@PathVariable String taskId, @PathVariable String queryId) {
        requireCompleted(taskId);
        SlowQueryRecord record = repository.readSlowQuery(taskId, queryId)
                .orElseThrow(() -> new NoSuchElementException("慢查询不存在：" + queryId));
        ParsedLogEntry entry = parser.parse(record.rawLine(), record.lineNumber(), record.fileIndex()).entry().orElse(null);
        return new SlowQueryDetail(record, entry == null ? null : entry.messageId(), entry == null ? null : entry.severity(),
                entry == null ? null : entry.component(), entry == null ? null : entry.context(), entry == null ? null : entry.message());
    }

    private void requireCompleted(String taskId) {
        AnalysisTask task = taskService.get(taskId);
        if (task.status() != TaskStatus.COMPLETED) {
            throw new TaskNotReadyException("任务尚未完成：" + taskId);
        }
    }

    private void validatePage(int page, int size, long minDurationMillis) {
        if (page < 1) {
            throw new IllegalArgumentException("page 必须大于或等于 1");
        }
        if (size < 1 || size > 200) {
            throw new IllegalArgumentException("size 必须在 1 到 200 之间");
        }
        if (minDurationMillis < 0) {
            throw new IllegalArgumentException("minDurationMillis 不能小于 0");
        }
    }

    private boolean contains(String actual, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return actual != null && actual.toLowerCase(Locale.ROOT)
                .contains(expected.trim().toLowerCase(Locale.ROOT));
    }
}
