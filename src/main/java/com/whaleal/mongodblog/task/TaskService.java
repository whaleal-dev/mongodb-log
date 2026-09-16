package com.whaleal.mongodblog.task;

import com.whaleal.mongodblog.storage.TaskRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class TaskService {
    private final Path dataDirectory;
    private final TaskRepository repository;
    private final TaskRunner runner;
    private final Executor executor;

    public TaskService(
            @Value("${mongodblog.data-dir}") String dataDirectory,
            TaskRepository repository,
            TaskRunner runner,
            @Qualifier("taskExecutor") Executor executor
    ) {
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
        this.repository = repository;
        this.runner = runner;
        this.executor = executor;
    }

    public AnalysisTask create(String name, List<MultipartFile> files) {
        validateFiles(files);
        String id = UUID.randomUUID().toString().replace("-", "");
        Path workDirectory = dataDirectory.resolve("work").resolve(id);
        List<TaskInputFile> inputs = new ArrayList<>();
        try {
            Files.createDirectories(workDirectory);
            for (int index = 0; index < files.size(); index++) {
                MultipartFile file = files.get(index);
                String originalName = safeFileName(file.getOriginalFilename(), index);
                String storedName = "%04d-%s".formatted(index, originalName);
                Path target = workDirectory.resolve(storedName).normalize();
                if (!target.startsWith(workDirectory)) {
                    throw new IllegalArgumentException("文件名包含非法路径");
                }
                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                inputs.add(new TaskInputFile(originalName, storedName, Files.size(target)));
            }
        } catch (IOException | RuntimeException e) {
            cleanup(workDirectory);
            throw new IllegalStateException("保存上传文件失败", e);
        }

        long totalBytes = inputs.stream().mapToLong(TaskInputFile::sizeBytes).sum();
        String taskName = name == null || name.isBlank() ? inputs.get(0).originalName() : name.trim();
        AnalysisTask task = new AnalysisTask(
                id, taskName, TaskStatus.QUEUED, System.currentTimeMillis(), null, null,
                List.copyOf(inputs), totalBytes, 0, 0, null, null, null
        );
        repository.saveTask(task);
        executor.execute(() -> runner.run(id));
        return task;
    }

    public AnalysisTask get(String id) {
        return repository.findTask(id).orElseThrow(() -> new NoSuchElementException("任务不存在：" + id));
    }

    public List<AnalysisTask> list() {
        return repository.listTasks();
    }

    public void delete(String id) {
        repository.deleteTask(id);
    }

    private void validateFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个日志文件");
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("日志文件不能为空");
            }
            String name = file.getOriginalFilename();
            if (name != null) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".zip") || lower.endsWith(".bz2") || lower.endsWith(".xz") || lower.endsWith(".7z")) {
                    throw new IllegalArgumentException("仅支持纯文本日志和 .gz 压缩日志");
                }
            }
        }
    }

    private String safeFileName(String originalName, int index) {
        if (originalName == null || originalName.isBlank()) {
            return "mongo-" + index + ".log";
        }
        String normalized = originalName.replace('\\', '/');
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\r\\n]", "_");
        return baseName.isBlank() ? "mongo-" + index + ".log" : baseName;
    }

    private void cleanup(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }
}
