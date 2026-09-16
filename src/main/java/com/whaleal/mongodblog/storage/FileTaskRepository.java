package com.whaleal.mongodblog.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.analysis.AnalysisSummary;
import com.whaleal.mongodblog.analysis.SlowQueryRecord;
import com.whaleal.mongodblog.analysis.diagnostics.LogDiagnostics;
import com.whaleal.mongodblog.task.AnalysisTask;
import com.whaleal.mongodblog.task.TaskStatus;
import com.whaleal.mongodblog.task.TaskActiveException;
import com.whaleal.mongodblog.task.TaskDeletionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Repository
public class FileTaskRepository implements TaskRepository {
    private static final TypeReference<List<AnalysisTask>> TASK_LIST_TYPE = new TypeReference<>() {
    };

    private final Path dataDirectory;
    private final Path tasksDirectory;
    private final Path indexFile;
    private final ObjectMapper objectMapper;
    private final Map<String, AnalysisTask> tasks = new LinkedHashMap<>();

    @Autowired
    public FileTaskRepository(@Value("${mongodblog.data-dir}") String dataDirectory, ObjectMapper objectMapper) {
        this(Path.of(dataDirectory), objectMapper);
    }

    public FileTaskRepository(Path dataDirectory, ObjectMapper objectMapper) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.tasksDirectory = this.dataDirectory.resolve("tasks");
        this.indexFile = this.dataDirectory.resolve("tasks-index.json");
        this.objectMapper = objectMapper;
        initialize();
    }

    @Override
    public synchronized void saveTask(AnalysisTask task) {
        tasks.put(task.id(), task);
        Path taskDirectory = tasksDirectory.resolve(task.id());
        writeJson(taskDirectory.resolve("metadata.json"), task);
        writeJson(indexFile, listTasks());
    }

    @Override
    public synchronized void deleteTask(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("任务 ID 包含非法字符");
        }
        AnalysisTask task = requireTask(id);
        if (task.status() != TaskStatus.COMPLETED && task.status() != TaskStatus.FAILED) {
            throw new TaskActiveException("排队中或分析中的任务不能删除");
        }
        try {
            validateDirectory(dataDirectory);
            validateDirectory(tasksDirectory);
            Path workDirectory = dataDirectory.resolve("work");
            validateDirectory(workDirectory);
            validateIndexFile(indexFile);
            validateIndexFile(indexFile.resolveSibling(indexFile.getFileName() + ".tmp"));
            List<Path> taskPaths = deletionPaths(tasksDirectory.resolve(id));
            List<Path> workPaths = deletionPaths(workDirectory.resolve(id));
            for (Path path : workPaths) {
                Files.delete(path);
            }
            for (Path path : taskPaths) {
                Files.delete(path);
            }
            writeJson(indexFile, listTasks().stream().filter(existing -> !existing.id().equals(id)).toList());
            tasks.remove(id);
        } catch (IOException | UncheckedIOException | IllegalStateException error) {
            throw new TaskDeletionException("删除任务失败，任务记录已保留，请检查本地文件权限或异常链接后重试", error);
        }
    }

    private List<Path> deletionPaths(Path directory) throws IOException {
        if (!validateDirectory(directory)) {
            return List.of();
        }
        try (var paths = Files.walk(directory)) {
            List<Path> result = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : result) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("任务目录包含符号链接：" + path.getFileName());
                }
            }
            return result;
        }
    }

    private boolean validateDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(directory);
        if (attributes == null) {
            return false;
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("任务存储路径不是普通目录：" + directory.getFileName());
        }
        return true;
    }

    private void validateIndexFile(Path path) throws IOException {
        BasicFileAttributes attributes = attributesIfPresent(path);
        if (attributes != null && (attributes.isSymbolicLink() || !attributes.isRegularFile())) {
            throw new IOException("任务索引路径不是普通文件：" + path.getFileName());
        }
    }

    private BasicFileAttributes attributesIfPresent(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    @Override
    public synchronized Optional<AnalysisTask> findTask(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public synchronized List<AnalysisTask> listTasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparingLong(AnalysisTask::createdAtEpochMillis).reversed())
                .toList();
    }

    @Override
    public synchronized void saveResult(String taskId, AnalysisSummary summary, List<SlowQueryRecord> slowQueries) {
        requireTask(taskId);
        Path taskDirectory = tasksDirectory.resolve(taskId);
        writeJson(taskDirectory.resolve("summary.json"), summary);
        writeJsonLines(taskDirectory.resolve("top-slow-queries.jsonl"), slowQueries);
    }

    @Override
    public synchronized void saveDiagnostics(String taskId, LogDiagnostics diagnostics) {
        requireTask(taskId);
        writeJson(tasksDirectory.resolve(taskId).resolve("diagnostics.json"), diagnostics);
    }

    @Override
    public AnalysisSummary readSummary(String taskId) {
        requireTask(taskId);
        Path path = tasksDirectory.resolve(taskId).resolve("summary.json");
        try {
            return objectMapper.readValue(path.toFile(), AnalysisSummary.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取任务汇总：" + taskId, e);
        }
    }

    @Override
    public Optional<LogDiagnostics> readDiagnostics(String taskId) {
        requireTask(taskId);
        Path path = tasksDirectory.resolve(taskId).resolve("diagnostics.json");
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(path.toFile(), LogDiagnostics.class));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取任务运行诊断：" + taskId, e);
        }
    }

    @Override
    public List<SlowQueryRecord> readSlowQueries(String taskId) {
        requireTask(taskId);
        Path path = tasksDirectory.resolve(taskId).resolve("top-slow-queries.jsonl");
        List<SlowQueryRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    records.add(objectMapper.readValue(line, SlowQueryRecord.class));
                }
            }
            return List.copyOf(records);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取慢查询明细：" + taskId, e);
        }
    }

    @Override
    public Optional<SlowQueryRecord> readSlowQuery(String taskId, String queryId) {
        return readSlowQueries(taskId).stream()
                .filter(record -> record.queryId().equals(queryId))
                .findFirst();
    }

    public Path dataDirectory() {
        return dataDirectory;
    }

    private synchronized void initialize() {
        try {
            Files.createDirectories(tasksDirectory);
            Files.createDirectories(dataDirectory.resolve("work"));
            if (Files.exists(indexFile)) {
                for (AnalysisTask task : objectMapper.readValue(indexFile.toFile(), TASK_LIST_TYPE)) {
                    tasks.put(task.id(), task);
                }
            }
            recoverInterruptedTasks();
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化任务数据目录：" + dataDirectory, e);
        }
    }

    private void recoverInterruptedTasks() {
        List<AnalysisTask> interrupted = tasks.values().stream()
                .filter(task -> task.status() == TaskStatus.RUNNING || task.status() == TaskStatus.QUEUED)
                .toList();
        long now = System.currentTimeMillis();
        for (AnalysisTask task : interrupted) {
            saveTask(task.failed(now, "应用在分析过程中退出"));
        }
    }

    private AnalysisTask requireTask(String id) {
        AnalysisTask task = tasks.get(id);
        if (task == null) {
            throw new NoSuchElementException("任务不存在：" + id);
        }
        return task;
    }

    private void writeJson(Path target, Object value) {
        writeAtomically(target, temporary -> objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value));
    }

    private void writeJsonLines(Path target, List<SlowQueryRecord> records) {
        writeAtomically(target, temporary -> {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                for (SlowQueryRecord record : records) {
                    writer.write(objectMapper.writeValueAsString(record));
                    writer.newLine();
                }
            }
        });
    }

    private void writeAtomically(Path target, IoWriter writer) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            writer.write(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法写入任务文件：" + target, e);
        }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(Path path) throws IOException;
    }
}
