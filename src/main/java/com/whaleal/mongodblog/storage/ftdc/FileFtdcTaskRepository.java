package com.whaleal.mongodblog.storage.ftdc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whaleal.mongodblog.task.TaskActiveException;
import com.whaleal.mongodblog.task.TaskDeletionException;
import com.whaleal.mongodblog.task.ftdc.FtdcTask;
import com.whaleal.mongodblog.task.ftdc.FtdcTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Repository
public class FileFtdcTaskRepository implements FtdcTaskRepository {
    private static final TypeReference<List<FtdcTask>> TASKS_TYPE = new TypeReference<>() { };

    private final Path root;
    private final Path tasksDirectory;
    private final Path workDirectory;
    private final Path indexFile;
    private final ObjectMapper objectMapper;
    private final Map<String, FtdcTask> tasks = new LinkedHashMap<>();

    @Autowired
    public FileFtdcTaskRepository(@Value("${mongodblog.data-dir}") String dataDirectory, ObjectMapper objectMapper) {
        this(Path.of(dataDirectory), objectMapper);
    }

    public FileFtdcTaskRepository(Path dataDirectory, ObjectMapper objectMapper) {
        this.root = dataDirectory.toAbsolutePath().normalize().resolve("ftdc");
        this.tasksDirectory = root.resolve("tasks");
        this.workDirectory = root.resolve("work");
        this.indexFile = root.resolve("tasks-index.json");
        this.objectMapper = objectMapper;
        initialize();
    }

    @Override
    public synchronized void saveTask(FtdcTask task) {
        tasks.put(task.id(), task);
        Path taskDirectory = taskDirectory(task.id());
        if (Files.isDirectory(taskDirectory) || task.status() == FtdcTaskStatus.FAILED) {
            writeJson(taskDirectory.resolve("metadata.json"), task);
        }
        writeJson(indexFile, listTasks());
    }

    @Override
    public synchronized Optional<FtdcTask> findTask(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public synchronized List<FtdcTask> listTasks() {
        return tasks.values().stream().sorted(Comparator.comparingLong(FtdcTask::createdAtEpochMillis).reversed()).toList();
    }

    @Override
    public synchronized void deleteTask(String id) {
        validateId(id);
        FtdcTask task = tasks.get(id);
        if (task == null) throw new NoSuchElementException("FTDC 任务不存在：" + id);
        if (task.status() != FtdcTaskStatus.COMPLETED && task.status() != FtdcTaskStatus.FAILED) {
            throw new TaskActiveException("排队中或分析中的 FTDC 任务不能删除");
        }
        try {
            deleteTree(taskDirectory(id));
            deleteTree(workDirectory(id));
            List<FtdcTask> remaining = listTasks().stream().filter(existing -> !existing.id().equals(id)).toList();
            writeJson(indexFile, remaining);
            tasks.remove(id);
        } catch (IOException | RuntimeException e) {
            throw new TaskDeletionException("删除 FTDC 任务失败，任务记录已保留", e);
        }
    }

    @Override
    public FtdcCatalog readCatalog(String id) {
        requireCompleted(id);
        try {
            return objectMapper.readValue(taskDirectory(id).resolve("catalog.json").toFile(), FtdcCatalog.class);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 FTDC catalog：" + id, e);
        }
    }

    @Override
    public Path taskDirectory(String id) {
        validateId(id);
        return tasksDirectory.resolve(id);
    }

    @Override
    public Path workDirectory(String id) {
        validateId(id);
        return workDirectory.resolve(id);
    }

    private void initialize() {
        try {
            Files.createDirectories(tasksDirectory);
            Files.createDirectories(workDirectory);
            if (Files.exists(indexFile)) {
                for (FtdcTask task : objectMapper.readValue(indexFile.toFile(), TASKS_TYPE)) tasks.put(task.id(), task);
            }
            long now = System.currentTimeMillis();
            for (FtdcTask task : List.copyOf(tasks.values())) {
                if (task.status() == FtdcTaskStatus.QUEUED || task.status() == FtdcTaskStatus.RUNNING) {
                    saveTask(task.failed(now, "应用在 FTDC 分析过程中退出"));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法初始化 FTDC 数据目录：" + root, e);
        }
    }

    private void requireCompleted(String id) {
        FtdcTask task = tasks.get(id);
        if (task == null) throw new NoSuchElementException("FTDC 任务不存在：" + id);
        if (task.status() != FtdcTaskStatus.COMPLETED) throw new IllegalStateException("FTDC 任务尚未完成：" + id);
    }

    private void deleteTree(Path directory) throws IOException {
        BasicFileAttributes rootAttributes = attributes(directory);
        if (rootAttributes == null) return;
        if (!rootAttributes.isDirectory() || rootAttributes.isSymbolicLink()) throw new IOException("FTDC 任务路径不是普通目录");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("FTDC 任务目录包含符号链接");
                Files.delete(path);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private BasicFileAttributes attributes(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法写入 FTDC 任务文件：" + target, e);
        }
    }

    private void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) throw new IllegalArgumentException("FTDC 任务 ID 非法");
    }
}
